package com.beauahill.hilltv.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beauahill.hilltv.core.CatalogKind
import com.beauahill.hilltv.core.CategoryGroup
import com.beauahill.hilltv.core.GroupedCategories
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.XtreamCategory
import com.beauahill.hilltv.core.XtreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Runs [block], turning any failure except cancellation into null. */
suspend fun <T> quietly(block: suspend () -> T): T? =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

/**
 * What Live TV is showing: a big pill (Favorites or a category group) and,
 * inside a group, either one provider category or all of them merged.
 */
class LiveTVModel(
    val client: XtreamClient,
    private val visibility: CategoryVisibilityStore,
    private val order: ChannelOrderStore,
) {
    sealed interface Pill {
        data object Favorites : Pill
        data class Group(val group: CategoryGroup) : Pill
    }

    data class Selection(
        val pill: Pill,
        /** A provider category ID, `ALL_ID` or `GAMES_ID`. Null for Favorites. */
        val sub: String?,
    )

    var categories: List<XtreamCategory> by mutableStateOf(emptyList())
        private set
    var categoryState: LoadState by mutableStateOf(LoadState.Idle)
        private set
    var selection: Selection? by mutableStateOf(null)
        private set

    /**
     * Category groups that have something to show, in pill order. Stored,
     * not computed: the screen reads it several times per frame, and
     * sorting every category into a group on each focus move would make
     * scrolling the channel list pay for it. See `refreshGroups()`.
     */
    var groups: List<GroupedCategories> by mutableStateOf(emptyList())
        private set
    var streams: List<LiveStream> by mutableStateOf(emptyList())
        private set
    var streamState: LoadState by mutableStateOf(LoadState.Idle)
        private set

    /** The channel the detail pane shows: the last one that held focus. */
    var detailStreamID: Int? by mutableStateOf(null)
        private set

    /** Channels across every Sports category, for matching games to channels. Empty until Games has been opened. */
    var sportsChannels: List<LiveStream> by mutableStateOf(emptyList())
        private set

    private val streamCache = HashMap<String, List<LiveStream>>()

    /** The sub-selection last used in each group, so coming back restores it. */
    private val lastSub = HashMap<CategoryGroup, String>()

    // MARK: Derived

    /** Categories the user hasn't hidden. */
    val visibleCategories: List<XtreamCategory> get() = visibility.visible(categories, CatalogKind.LIVE)

    val allHidden: Boolean get() = categories.isNotEmpty() && visibleCategories.isEmpty()

    val showsFavorites: Boolean get() = selection?.pill == Pill.Favorites

    /** The selected group's provider categories. Empty for Favorites. */
    val subcategories: List<XtreamCategory>
        get() {
            val group = (selection?.pill as? Pill.Group)?.group ?: return emptyList()
            return groups.firstOrNull { it.group == group }?.categories ?: emptyList()
        }

    val showsGames: Boolean get() = selection?.sub == GAMES_ID

    /** Whether the selected group offers "Games". Only Sports does. */
    val offersGames: Boolean get() = selection?.pill == Pill.Group(CategoryGroup.SPORTS)

    /** Whether the selected group offers the merged "All" list. Sports doesn't: its Games list already ends with every sports channel. */
    val offersAll: Boolean get() = !offersGames && offersAll(subcategories)

    // MARK: The viewer's order

    /**
     * The provider category on screen, when the list is one category.
     * Only those can be put in the viewer's own order: "All" and Games are
     * built from several categories, and Favorites keeps its own order.
     */
    val reorderCategoryID: String?
        get() {
            val current = selection ?: return null
            if (current.pill !is Pill.Group) return null
            return current.sub?.takeIf { it != ALL_ID && it != GAMES_ID }
        }

    val hasCustomOrder: Boolean get() = reorderCategoryID?.let(order::hasOrder) ?: false

    fun move(streamID: Int, index: Int) {
        val categoryID = reorderCategoryID ?: return
        order.move(streamID, index, streams, categoryID)
        applyOrder()
    }

    fun resetOrder() {
        val categoryID = reorderCategoryID ?: return
        order.reset(categoryID)
        applyOrder()
    }

    /**
     * `streams` is stored, not sorted per render. This runs after a move
     * and when Live TV appears, since the Guide can reorder the same
     * category.
     */
    private fun applyOrder() {
        val categoryID = reorderCategoryID ?: return
        val cached = streamCache[categoryID] ?: return
        streams = order.apply(cached, categoryID)
    }

    // MARK: Loading

    suspend fun loadCategories(startWithFavorites: Boolean) {
        if (selection == null && startWithFavorites) select(Pill.Favorites)
        if (categories.isNotEmpty()) {
            reconcileSelection()
            return
        }
        if (categoryState == LoadState.Loading) return
        categoryState = LoadState.Loading
        try {
            categories = client.liveCategories()
            categoryState = if (categories.isEmpty()) LoadState.Empty else LoadState.Loaded
        } catch (error: CancellationException) {
            // Leaving the section cancels the request; retry next time instead of showing an error.
            categoryState = LoadState.Idle
            throw error
        } catch (error: Exception) {
            categoryState = LoadState.Failed(error.readable())
            return
        }
        reconcileSelection()
    }

    suspend fun retryCategories(startWithFavorites: Boolean) {
        categoryState = LoadState.Idle
        loadCategories(startWithFavorites)
    }

    suspend fun retryStreams() {
        streamState = LoadState.Idle
        load()
    }

    /** Null clears it, for when a game row has focus instead of a channel. */
    fun showDetail(streamID: Int?) {
        detailStreamID = streamID
    }

    // MARK: Selecting

    /** Pills change on click, not on focus: stepping across the row shouldn't reload the channel list at every stop. */
    suspend fun select(pill: Pill) {
        if (selection?.pill == pill && (streamState == LoadState.Loaded || streamState == LoadState.Loading)) return
        selection = when (pill) {
            Pill.Favorites -> Selection(pill, null)
            is Pill.Group -> {
                val categories = groups.firstOrNull { it.group == pill.group }?.categories ?: emptyList()
                val remembered = lastSub[pill.group]?.takeIf { it in validSubs(pill.group, categories) }
                Selection(pill, remembered ?: defaultSub(pill.group, categories))
            }
        }
        detailStreamID = null
        load()
    }

    /** Picks a provider category, "All" or "Games" inside the selected group. */
    suspend fun selectSub(sub: String) {
        val current = selection ?: return
        val group = (current.pill as? Pill.Group)?.group ?: return
        if (current.sub == sub && (streamState == LoadState.Loaded || streamState == LoadState.Loading)) return
        selection = current.copy(sub = sub)
        lastSub[group] = sub
        detailStreamID = null
        load()
    }

    private fun validSubs(group: CategoryGroup, categories: List<XtreamCategory>): List<String> {
        val subs = categories.map { it.id }.toMutableList()
        if (group == CategoryGroup.SPORTS) {
            subs.add(GAMES_ID)
        } else if (offersAll(categories)) {
            subs.add(ALL_ID)
        }
        return subs
    }

    private fun defaultSub(group: CategoryGroup, categories: List<XtreamCategory>): String? {
        if (group == CategoryGroup.SPORTS) return GAMES_ID
        return if (offersAll(categories)) ALL_ID else categories.firstOrNull()?.id
    }

    /**
     * Keeps the selection on something that is still shown, since
     * categories can be hidden in Settings between visits. Also refetches
     * after a cancelled first load.
     */
    private suspend fun reconcileSelection() {
        refreshGroups()
        val groups = groups
        val selection = selection
        if (selection == null) {
            selectFirst(groups)
            return
        }
        val group = (selection.pill as? Pill.Group)?.group ?: return
        val grouped = groups.firstOrNull { it.group == group }
        if (grouped == null) {
            selectFirst(groups)
            return
        }
        val sub = selection.sub
        if (sub != null && sub in validSubs(group, grouped.categories)) {
            // "All" and "Games" depend on which categories are visible, so rebuild them.
            if (streamState == LoadState.Idle || sub == ALL_ID || sub == GAMES_ID) load() else applyOrder()
        } else {
            lastSub.remove(group)
            this.selection = Selection(selection.pill, defaultSub(group, grouped.categories))
            detailStreamID = null
            load()
        }
    }

    /**
     * Runs whenever Live TV appears, which covers both ways the groups can
     * change: categories arriving, and categories hidden or shown in
     * Settings while Live TV was off screen.
     */
    private fun refreshGroups() {
        groups = CategoryGroup.grouping(visibleCategories)
    }

    private suspend fun selectFirst(groups: List<GroupedCategories>) {
        val first = groups.firstOrNull()
        select(if (first != null) Pill.Group(first.group) else Pill.Favorites)
    }

    // MARK: Channels for the selection

    private suspend fun load() {
        val token = selection ?: return
        val group = (token.pill as? Pill.Group)?.group
        if (group == null) {
            // Favorites: the screen reads the channels from FavoritesStore.
            streams = emptyList()
            streamState = LoadState.Loaded
            return
        }
        val grouped = groups.firstOrNull { it.group == group }?.categories ?: emptyList()
        val sub = token.sub
        if (sub == null) {
            streams = emptyList()
            streamState = LoadState.Empty
            return
        }

        try {
            if (sub == ALL_ID || sub == GAMES_ID) {
                val missing = grouped.filter { streamCache[it.id] == null }
                if (missing.isNotEmpty()) {
                    streams = emptyList()
                    // Games come from the scores feed and can show while the channels load.
                    streamState = if (sub == GAMES_ID) LoadState.Loaded else LoadState.Loading
                    fetch(missing)
                    if (selection != token) return
                }
                val merged = merge(grouped.mapNotNull { streamCache[it.id] })
                if (sub == GAMES_ID) sportsChannels = merged
                if (merged.isEmpty() && grouped.any { streamCache[it.id] == null }) {
                    streams = emptyList()
                    streamState = LoadState.Failed("Couldn't load these channels.")
                } else {
                    streams = merged
                    streamState = if (merged.isEmpty()) LoadState.Empty else LoadState.Loaded
                }
                return
            }

            val cached = streamCache[sub]
            if (cached != null) {
                streams = order.apply(cached, sub)
                streamState = if (cached.isEmpty()) LoadState.Empty else LoadState.Loaded
                return
            }
            streams = emptyList()
            streamState = LoadState.Loading
            val result = client.liveStreams(sub)
            streamCache[sub] = result
            // Ignore late results if the user already moved on.
            if (selection != token) return
            streams = order.apply(result, sub)
            streamState = if (result.isEmpty()) LoadState.Empty else LoadState.Loaded
        } catch (error: CancellationException) {
            if (selection == token) streamState = LoadState.Idle
            throw error
        } catch (error: Exception) {
            if (selection == token) streamState = LoadState.Failed(error.readable())
        }
    }

    /** Fetches categories four at a time. Failures are skipped: a merged list with one category missing beats no list. */
    private suspend fun fetch(categories: List<XtreamCategory>) {
        for (batch in categories.chunked(4)) {
            val results = coroutineScope {
                batch.map { category -> async { category.id to quietly { client.liveStreams(category.id) } } }.awaitAll()
            }
            for ((id, streams) in results) {
                if (streams != null) streamCache[id] = streams
            }
        }
    }

    companion object {
        /** Sub-selection: every category in the group, merged into one list. */
        const val ALL_ID = "hilltv.all"

        /** Sub-selection in Sports: games from the scores feed, then every sports channel. */
        const val GAMES_ID = "hilltv.games"

        /** Groups with more categories than this don't offer "All": it would mean that many requests and a list too long to be useful. */
        const val MERGE_LIMIT = 12

        private fun offersAll(categories: List<XtreamCategory>): Boolean = categories.size in 2..MERGE_LIMIT

        /**
         * One list from several categories, in order, without repeats. A channel
         * listed under two categories would otherwise give two rows the same identity.
         */
        private fun merge(lists: List<List<LiveStream>>): List<LiveStream> {
            val seen = HashSet<Int>()
            return lists.flatten().filter { seen.add(it.id) }
        }
    }
}
