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
import com.beauahill.hilltv.model.CategoryVisibilityStore
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

    // MARK: Categories and channels (same pattern as Movies)

    suspend fun loadCategories() {
        refreshWindowIfNeeded()
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
     * cancelled first load.
     */
    private suspend fun reconcileSelection() {
        val shown = visibleCategories
        val id = selectedCategoryID
        if (id != null && shown.any { it.id == id }) {
            if (channelState == LoadState.Idle) select(id)
        } else if (shown.isNotEmpty()) {
            select(shown.first().id)
        } else {
            selectedCategoryID = null
            channels = emptyList()
            channelState = LoadState.Idle
        }
    }

    suspend fun select(categoryID: String) {
        if (categoryID == selectedCategoryID && (channelState == LoadState.Loaded || channelState == LoadState.Loading)) return
        selectedCategoryID = categoryID
        channelCache[categoryID]?.let { cached ->
            channels = cached
            channelState = if (cached.isEmpty()) LoadState.Empty else LoadState.Loaded
            return
        }
        channels = emptyList()
        channelState = LoadState.Loading
        try {
            val result = client.liveStreams(categoryID)
            channelCache[categoryID] = result
            if (selectedCategoryID != categoryID) return
            channels = result
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

    private companion object {
        const val MAX_CONCURRENT = 4
        const val ENTRIES_PER_CHANNEL = 12
    }
}

private val channelColumn = 125.dp
private val guideRowHeight = 44.dp

@Composable
fun GuideScreen(model: GuideModel) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val focusSelect = rememberFocusSelect { model.select(it) }
    // Moves the "now" line and the on-air shading along.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }

    LaunchedEffect(model) { model.loadCategories() }
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

    fun toggleFavorite(stream: LiveStream) {
        val wasFavorite = app.favorites.contains(stream.id)
        app.favorites.toggle(stream)
        app.flash(if (wasFavorite) "Removed from Favorites" else "Added to Favorites")
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(160.dp).fillMaxHeight()) {
            CategoryColumn(
                "Guide", model.visibleCategories, model.selectedCategoryID,
                onFocus = { focusSelect(it.id) },
                onSelect = { scope.launch { model.select(it.id) } },
            )
            LoadStateView(
                if (model.allHidden) LoadState.Empty else model.categoryState,
                emptyText = if (model.allHidden) ALL_CATEGORIES_HIDDEN_TEXT else "No live categories",
            )
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
                        items(model.channels, key = { it.id }) { stream ->
                            DisposableEffect(stream.id) {
                                model.rowAppeared(stream.id)
                                onDispose { model.rowDisappeared(stream.id) }
                            }
                            GuideRow(
                                stream, window, model.listings[stream.id], timeline, now,
                                onPlay = { play(stream, it) },
                                onLongClick = { toggleFavorite(stream) },
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
            LoadStateView(model.channelState, emptyText = "No channels in this category") {
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
            Txt(stream.name, style = Type.small, color = Theme.text, maxLines = 2, weight = FontWeight.SemiBold)
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
