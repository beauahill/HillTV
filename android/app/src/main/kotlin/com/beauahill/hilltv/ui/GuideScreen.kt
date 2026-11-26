package com.beauahill.hilltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauahill.hilltv.core.CatalogKind
import com.beauahill.hilltv.core.EPGEntry
import com.beauahill.hilltv.core.GuideWindow
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.XtreamCategory
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.core.displayName
import com.beauahill.hilltv.model.CategoryVisibilityStore
import com.beauahill.hilltv.model.ChannelOrderStore
import com.beauahill.hilltv.model.LoadState
import com.beauahill.hilltv.model.quietly
import com.beauahill.hilltv.model.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * TV guide: categories on the left, a timeline grid of channels on the
 * right. Listings come from the panel's short EPG, fetched per row once it
 * has stayed on screen for a moment, a few at a time, and cached. The full
 * XMLTV guide (often 10-100 MB) isn't downloaded yet.
 *
 * Main thread only.
 */
class GuideModel(
    val client: XtreamClient,
    private val visibility: CategoryVisibilityStore,
    private val order: ChannelOrderStore,
    private val scope: CoroutineScope,
) {
    var categories: List<XtreamCategory> by mutableStateOf(emptyList())
        private set
    var categoryState: LoadState by mutableStateOf(LoadState.Idle)
        private set
    var selectedCategoryID: String? by mutableStateOf(null)
        private set
    var channels: List<LiveStream> by mutableStateOf(emptyList())
        private set
    var channelState: LoadState by mutableStateOf(LoadState.Idle)
        private set
    val listings = mutableStateMapOf<Int, List<EPGEntry>>()
    var window: GuideWindow by mutableStateOf(GuideWindow.starting(Instant.now(), hours = 3.0))
        private set

    private val channelCache = HashMap<String, List<LiveStream>>()
    private val appearJobs = HashMap<Int, Job>()

    /** Window start each channel's listings were fetched for. Older means stale. */
    private val fetchedFor = HashMap<Int, Instant>()

    /** Rows on screen now, so a window change can refresh them. */
    private val visible = HashSet<Int>()
    private val waiting = ArrayList<Int>()
    private val queued = HashSet<Int>()
    private var active = 0

    /** Categories the user hasn't hidden. */
    val visibleCategories: List<XtreamCategory> get() = visibility.visible(categories, CatalogKind.LIVE)

    val allHidden: Boolean get() = categories.isNotEmpty() && visibleCategories.isEmpty()

    /**
     * True when the Guide lists favorites. The screen reads those channels
     * from `FavoritesStore`, so adding or removing one shows at once.
     */
    val showsFavorites: Boolean get() = selectedCategoryID == FAVORITES_ID

    // MARK: The viewer's order

    /** The provider category on screen. Null for Favorites, which keeps its own order in `FavoritesStore`. */
    val reorderCategoryID: String? get() = selectedCategoryID?.takeIf { it != FAVORITES_ID }

    val hasCustomOrder: Boolean get() = reorderCategoryID?.let(order::hasOrder) ?: false

    fun move(streamID: Int, index: Int) {
        val categoryID = reorderCategoryID ?: return
        order.move(streamID, index, channels, categoryID)
        applyOrder()
    }

    fun resetOrder() {
        val categoryID = reorderCategoryID ?: return
        order.reset(categoryID)
        applyOrder()
    }

    /**
     * `channels` is stored, not sorted per render. This runs after a move
     * and when the Guide appears, since Live TV can reorder the same category.
     */
    private fun applyOrder() {
        val categoryID = reorderCategoryID ?: return
        val cached = channelCache[categoryID] ?: return
        channels = order.apply(cached, categoryID)
    }

    // MARK: Categories and channels (same pattern as Movies)

    suspend fun loadCategories(startWithFavorites: Boolean) {
        refreshWindowIfNeeded()
        if (selectedCategoryID == null && startWithFavorites) select(FAVORITES_ID)
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
            categoryState = LoadState.Idle
            throw error
        } catch (error: Exception) {
            categoryState = LoadState.Failed(error.readable())
            return
        }
        reconcileSelection()
    }

    /**
     * Keeps the selection on a category that is still shown, since categories
     * can be hidden in Settings between visits. Also refetches after a
     * cancelled first load. Favorites is never hidden, and it is where the
     * Guide goes when every category is.
     */
    private suspend fun reconcileSelection() {
        if (showsFavorites) return
        val shown = visibleCategories
        val id = selectedCategoryID
        if (id != null && shown.any { it.id == id }) {
            if (channelState == LoadState.Idle) select(id) else applyOrder()
        } else if (shown.isNotEmpty()) {
            select(shown.first().id)
        } else {
            select(FAVORITES_ID)
        }
    }

    suspend fun select(categoryID: String) {
        if (categoryID == selectedCategoryID && (channelState == LoadState.Loaded || channelState == LoadState.Loading)) return
        selectedCategoryID = categoryID
        if (categoryID == FAVORITES_ID) {
            // Nothing to fetch: the screen lists the channels from FavoritesStore.
            channels = emptyList()
            channelState = LoadState.Loaded
            return
        }
        channelCache[categoryID]?.let { cached ->
            channels = order.apply(cached, categoryID)
            channelState = if (cached.isEmpty()) LoadState.Empty else LoadState.Loaded
            return
        }
        channels = emptyList()
        channelState = LoadState.Loading
        try {
            val result = client.liveStreams(categoryID)
            channelCache[categoryID] = result
            if (selectedCategoryID != categoryID) return
            channels = order.apply(result, categoryID)
            channelState = if (result.isEmpty()) LoadState.Empty else LoadState.Loaded
        } catch (error: CancellationException) {
            if (selectedCategoryID == categoryID) channelState = LoadState.Idle
            throw error
        } catch (error: Exception) {
            if (selectedCategoryID == categoryID) channelState = LoadState.Failed(error.readable())
        }
    }

    suspend fun retryChannels() {
        val id = selectedCategoryID ?: return
        channelState = LoadState.Idle
        select(id)
    }

    /** Moves the window forward once the current half hour has passed. */
    fun refreshWindowIfNeeded() {
        val current = GuideWindow.starting(Instant.now(), hours = 3.0)
        if (current.start == window.start) return
        window = current
        // A TV app can stay open for days; refetch what's on screen for the new window.
        for (streamID in visible.toList()) {
            if (needsFetch(streamID)) enqueue(streamID)
        }
    }

    // MARK: Listings, fetched lazily per visible row

    private fun needsFetch(streamID: Int): Boolean = fetchedFor[streamID] != window.start

    fun rowAppeared(streamID: Int) {
        visible.add(streamID)
        if (!needsFetch(streamID) || streamID in queued || appearJobs[streamID] != null) return
        // Only fetch rows that stay visible, so fast scrolling doesn't flood the panel.
        appearJobs[streamID] = scope.launch {
            delay(300)
            appearJobs.remove(streamID)
            enqueue(streamID)
        }
    }

    fun rowDisappeared(streamID: Int) {
        visible.remove(streamID)
        appearJobs.remove(streamID)?.cancel()
        if (waiting.remove(streamID)) queued.remove(streamID)
    }

    private fun enqueue(streamID: Int) {
        if (!needsFetch(streamID) || streamID in queued) return
        queued.add(streamID)
        waiting.add(streamID)
        pump()
    }

    private fun pump() {
        while (active < MAX_CONCURRENT && waiting.isNotEmpty()) {
            val streamID = waiting.removeAt(waiting.lastIndex)
            active += 1
            val windowStart = window.start
            scope.launch {
                try {
                    val entries = quietly { client.shortEPG(streamID, ENTRIES_PER_CHANNEL) }
                    if (entries != null) {
                        listings[streamID] = entries
                        fetchedFor[streamID] = windowStart
                    } else if (listings[streamID] == null) {
                        // Failed: show the empty state, but don't mark it fetched,
                        // so the row tries again next time it comes on screen.
                        listings[streamID] = emptyList()
                    }
                } finally {
                    queued.remove(streamID)
                    active -= 1
                }
                pump()
            }
        }
    }

    companion object {
        /** Stands in for a category ID when the Guide shows favorite channels. */
        const val FAVORITES_ID = "hilltv.favorites"

        private const val MAX_CONCURRENT = 4
        private const val ENTRIES_PER_CHANNEL = 12
    }
}

private val channelColumn = 125.dp
private val guideRowHeight = 44.dp

@Composable
fun GuideScreen(model: GuideModel) {
    val app = LocalApp.current
    val menus = LocalHoldMenu.current
    val scope = rememberCoroutineScope()
    val focusSelect = rememberFocusSelect { model.select(it) }
    /** Set to move a channel within the list on screen. */
    var moving by remember { mutableStateOf<ChannelMove?>(null) }
    // Moves the "now" line and the on-air shading along.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }

    LaunchedEffect(model) { model.loadCategories(startWithFavorites = app.favorites.channels.isNotEmpty()) }
    LaunchedEffect(model) {
        while (true) {
            delay(60_000)
            model.refreshWindowIfNeeded()
        }
    }

    fun play(stream: LiveStream, entry: EPGEntry?) {
        val onAir = model.listings[stream.id]?.firstOrNull { it.isOnAir(Instant.now()) }
        app.play(liveItem(stream, model.client, app, subtitle = onAir?.title ?: entry?.title))
    }

    /** "Favorites" sits above the provider's categories and is never hidden. */
    val favoritesEntry = remember { XtreamCategory(GuideModel.FAVORITES_ID, "Favorites") }
    val rows = if (model.showsFavorites) app.favorites.channels else model.channels
    val channelState = when {
        !model.showsFavorites -> model.channelState
        app.favorites.channels.isEmpty() -> LoadState.Empty
        else -> LoadState.Loaded
    }
    val emptyText = when {
        !model.showsFavorites -> "No channels in this category"
        model.allHidden -> ALL_CATEGORIES_HIDDEN_TEXT
        else -> "No favorites yet. Hold Select on a channel to add it."
    }
    val listTitle = if (model.showsFavorites) {
        "Favorites"
    } else {
        model.categories.firstOrNull { it.id == model.selectedCategoryID }?.displayName?.title ?: "Guide"
    }

    fun openMenu(stream: LiveStream) {
        menus.show(
            stream.displayName.title,
            buildList {
                add(favoriteAction(app, stream))
                add(multiviewAction(app, stream))
                if (rows.size > 1) add(MenuAction("Move") { moving = ChannelMove(listTitle, rows, stream) })
                if (model.hasCustomOrder) add(MenuAction("Use the provider's order") { model.resetOrder() })
            },
        )
    }

    MoveChannelCover(moving, onClose = { moving = null }) { move, index ->
        if (model.showsFavorites) app.favorites.move(move.stream.id, index) else model.move(move.stream.id, index)
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(160.dp).fillMaxHeight()) {
            CategoryColumn(
                "Guide", listOf(favoritesEntry) + model.visibleCategories, model.selectedCategoryID,
                onFocus = { focusSelect(it.id) },
                onSelect = { scope.launch { model.select(it.id) } },
            )
            LoadStateView(model.categoryState, emptyText = "No live categories")
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val timeline = maxOf(maxWidth - channelColumn, 100.dp)
            val window = model.window
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Slot labels along the top.
                Box(Modifier.fillMaxWidth().height(16.dp)) {
                    for (slot in window.slots()) {
                        Txt(
                            slot.shortTime,
                            Modifier.offset(x = channelColumn + timeline * window.fraction(slot).toFloat() + 4.dp),
                            Type.small, Theme.muted, maxLines = 1, weight = FontWeight.SemiBold,
                        )
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize().focusRestorer(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        items(rows, key = { it.id }) { stream ->
                            DisposableEffect(stream.id) {
                                model.rowAppeared(stream.id)
                                onDispose { model.rowDisappeared(stream.id) }
                            }
                            GuideRow(
                                stream, window, model.listings[stream.id], timeline, now,
                                onPlay = { play(stream, it) },
                                onLongClick = { openMenu(stream) },
                            )
                        }
                    }
                    // The "now" line.
                    Box(
                        Modifier
                            .offset(x = channelColumn + timeline * window.fraction(now).toFloat())
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .background(Theme.live)
                    )
                }
            }
            LoadStateView(channelState, emptyText = emptyText) {
                scope.launch { model.retryChannels() }
            }
        }
    }
}

@Composable
private fun GuideRow(
    stream: LiveStream,
    window: GuideWindow,
    entries: List<EPGEntry>?,
    timeline: Dp,
    now: Instant,
    onPlay: (EPGEntry?) -> Unit,
    onLongClick: () -> Unit,
) {
    Row(Modifier.height(guideRowHeight), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(channelColumn).padding(end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(stream.iconURL, width = 40.dp, height = 26.dp)
            ChannelNameLabel(
                stream.displayName,
                style = Type.small, detailStyle = TextStyle(fontSize = 9.sp), titleLines = 2,
                weight = FontWeight.SemiBold, color = Theme.text,
            )
        }
        Row(Modifier.width(timeline)) {
            // Positional keys: when listings arrive, the focused first cell stays the same cell.
            window.cells(entries ?: emptyList()).forEachIndexed { index, cell ->
                key(index) {
                    GuideCell(cell, isLoading = entries == null, now, Modifier.width(timeline * cell.width.toFloat()), { onPlay(cell.entry) }, onLongClick)
                }
            }
        }
    }
}

/**
 * Guide cells show focus with a fill only: no scale and no ring, so
 * neighbouring cells and row edges never clip or overlap.
 */
@Composable
private fun GuideCell(
    cell: GuideWindow.Cell,
    isLoading: Boolean,
    now: Instant,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val entry = cell.entry
    FocusBox(onClick, modifier.height(guideRowHeight), onLongClick = onLongClick) { focused, pressed ->
        val fill = when {
            focused -> Theme.accent
            entry == null -> Theme.surface
            entry.isOnAir(now) -> Theme.controlRaised
            else -> Theme.control
        }
        Box(
            Modifier
                .fillMaxSize()
                .alpha(if (pressed) 0.85f else 1f)
                .padding(1.5.dp)
                .background(fill, RoundedCornerShape(5.dp))
                .clipToBounds()
                .padding(horizontal = 5.5.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val primary = if (focused) Theme.onAccent else Theme.text
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (entry != null) {
                    Txt(entry.title, style = Type.small, color = primary, maxLines = 1, weight = FontWeight.SemiBold)
                    Txt("${entry.start.shortTime} to ${entry.end.shortTime}", style = TextStyle(fontSize = 9.sp), color = secondaryColor(), maxLines = 1)
                } else {
                    Txt(if (isLoading) "Loading" else "No guide data", style = Type.small, color = secondaryColor(), maxLines = 1)
                }
            }
        }
    }
}
