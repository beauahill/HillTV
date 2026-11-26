package com.beauahill.hilltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauahill.hilltv.core.CatalogKind
import com.beauahill.hilltv.core.Episode
import com.beauahill.hilltv.core.SeriesInfo
import com.beauahill.hilltv.core.SeriesItem
import com.beauahill.hilltv.core.WatchEntry
import com.beauahill.hilltv.core.XtreamCategory
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.model.CategoryVisibilityStore
import com.beauahill.hilltv.model.LoadState
import com.beauahill.hilltv.model.readable
import com.beauahill.hilltv.player.PlayableItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SeriesModel(val client: XtreamClient, private val visibility: CategoryVisibilityStore) {
    var categories: List<XtreamCategory> by mutableStateOf(emptyList())
        private set
    var categoryState: LoadState by mutableStateOf(LoadState.Idle)
        private set
    var selectedCategoryID: String? by mutableStateOf(null)
        private set
    var items: List<SeriesItem> by mutableStateOf(emptyList())
        private set
    var itemState: LoadState by mutableStateOf(LoadState.Idle)
        private set

    /** Open detail pages. Empty means Series is at its root. */
    val path = mutableStateListOf<Route>()

    private val cache = HashMap<String, List<SeriesItem>>()

    /** Categories the user hasn't hidden. */
    val visibleCategories: List<XtreamCategory> get() = visibility.visible(categories, CatalogKind.SERIES)

    val allHidden: Boolean get() = categories.isNotEmpty() && visibleCategories.isEmpty()

    suspend fun loadCategories() {
        if (categories.isNotEmpty()) {
            reconcileSelection()
            return
        }
        if (categoryState == LoadState.Loading) return
        categoryState = LoadState.Loading
        try {
            categories = client.seriesCategories()
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

    /**
     * Keeps the selection on a category that is still shown, since categories
     * can be hidden in Settings between visits. Also refetches after a
     * cancelled first load.
     */
    private suspend fun reconcileSelection() {
        val visible = visibleCategories
        val id = selectedCategoryID
        if (id != null && visible.any { it.id == id }) {
            if (itemState == LoadState.Idle) select(id)
        } else if (visible.isNotEmpty()) {
            select(visible.first().id)
        } else {
            selectedCategoryID = null
            items = emptyList()
            itemState = LoadState.Idle
        }
    }

    suspend fun select(categoryID: String) {
        if (categoryID == selectedCategoryID && (itemState == LoadState.Loaded || itemState == LoadState.Loading)) return
        selectedCategoryID = categoryID
        cache[categoryID]?.let { cached ->
            items = cached
            itemState = if (cached.isEmpty()) LoadState.Empty else LoadState.Loaded
            return
        }
        items = emptyList()
        itemState = LoadState.Loading
        try {
            val result = client.series(categoryID)
            cache[categoryID] = result
            if (selectedCategoryID != categoryID) return
            items = result
            itemState = if (result.isEmpty()) LoadState.Empty else LoadState.Loaded
        } catch (error: CancellationException) {
            if (selectedCategoryID == categoryID) itemState = LoadState.Idle
            throw error
        } catch (error: Exception) {
            if (selectedCategoryID == categoryID) itemState = LoadState.Failed(error.readable())
        }
    }

    suspend fun retry() {
        val id = selectedCategoryID ?: return
        itemState = LoadState.Idle
        select(id)
    }
}

/** First four digits of the release date, when the panel sends one. */
val SeriesItem.seriesYear: String?
    get() {
        val date = releaseDate ?: return null
        if (date.length < 4) return null
        val year = date.take(4)
        return if (year.all { it in '0'..'9' }) year else null
    }

@Composable
fun SeriesScreen(model: SeriesModel) {
    val scope = rememberCoroutineScope()
    val focusSelect = rememberFocusSelect { model.select(it) }
    LaunchedEffect(model) { model.loadCategories() }

    DetailStack(model.path, model.client) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(180.dp).fillMaxHeight()) {
                CategoryColumn(
                    "Series", model.visibleCategories, model.selectedCategoryID,
                    onFocus = { focusSelect(it.id) },
                    onSelect = { scope.launch { model.select(it.id) } },
                )
                LoadStateView(
                    if (model.allHidden) LoadState.Empty else model.categoryState,
                    emptyText = if (model.allHidden) ALL_CATEGORIES_HIDDEN_TEXT else "No series categories",
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // A new grid per category, so each one opens at its top instead of
                // where the last one was scrolled to.
                key(model.selectedCategoryID) {
                    PosterGrid(model.items, key = { it.id }, Modifier.fillMaxSize()) { item ->
                        TVCard(onClick = { model.path.add(Route.Series(item)) }) { focused ->
                            PosterCard(item.name, item.coverURL, focused, subtitle = item.seriesYear)
                        }
                    }
                }
                LoadStateView(model.itemState, emptyText = "No series in this category") { scope.launch { model.retry() } }
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(client: XtreamClient, series: SeriesItem) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SeriesInfo?>(null) }
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    val firstEpisode = remember { FocusRequester() }

    val currentSeason = info?.let { loaded -> loaded.seasons.firstOrNull { it.number == selectedSeason } ?: loaded.seasons.firstOrNull() }
    val cover = info?.coverURL ?: series.coverURL

    val pills = buildList {
        series.seriesYear?.let { add(it) }
        series.genre?.takeIf { it.isNotEmpty() }?.let { add(it) }
        series.rating?.takeIf { it > 0 }?.let { add(ratingLabel(it)) }
        info?.seasons?.takeIf { it.isNotEmpty() }?.let { add(if (it.size == 1) "1 season" else "${it.size} seasons") }
    }

    suspend fun load() {
        if (info != null) return
        state = LoadState.Loading
        try {
            val result = client.seriesInfo(series.id)
            info = result
            selectedSeason = result.seasons.firstOrNull()?.number
            state = if (result.seasons.isEmpty()) LoadState.Empty else LoadState.Loaded
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state = LoadState.Failed(error.readable())
        }
    }

    fun play(episode: Episode) {
        val season = episode.season ?: currentSeason?.number
        val label = listOfNotNull(season?.let { "S$it" }, episode.episodeNumber?.let { "E$it" }).joinToString("")
        val subtitle = if (label.isEmpty()) episode.title else "$label · ${episode.title}"
        val template = WatchEntry(
            kind = WatchEntry.Kind.EPISODE,
            streamID = episode.id,
            containerExtension = episode.containerExtension,
            title = series.name,
            subtitle = subtitle,
            artworkURL = cover,
            position = 0.0,
            duration = null,
        )
        // Episodes resume automatically; the row's bar shows how far in.
        app.play(
            PlayableItem(
                id = "episode-${episode.id}",
                title = series.name,
                subtitle = subtitle,
                url = client.episodeURL(episode),
                isLive = false,
                history = template,
                startAt = app.watchHistory.entry(template.id)?.position,
            )
        )
    }

    LaunchedEffect(series.id) { load() }

    // Once the episodes are in, start on the first one.
    LaunchedEffect(info) {
        if (info?.seasons?.isNotEmpty() == true) {
            try {
                firstEpisode.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(cover, Modifier.bleed())
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(120.dp, 180.dp).clip(RoundedCornerShape(Theme.radius)).background(Theme.control)) {
                    RemoteImage(cover, Modifier.fillMaxSize(), ContentScale.Crop)
                }
                Eyebrow("Series")
                Txt(series.name, style = Type.subtitle, color = Theme.text, maxLines = 3)
                PillFlow(pills)
                (info?.plot ?: series.plot)?.let {
                    Txt(it, style = Type.small.copy(lineHeight = 15.sp), color = Theme.muted, maxLines = 6)
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Episodes", Modifier.padding(start = 6.dp))

                val seasons = info?.seasons ?: emptyList()
                if (seasons.size > 1) {
                    val selected = remember { FocusRequester() }
                    LazyRow(
                        Modifier.focusRestorer(selected),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(seasons, key = { it.number }) { season ->
                            val isSelected = season.number == currentSeason?.number
                            TVAction(
                                "Season ${season.number}",
                                onClick = { selectedSeason = season.number },
                                modifier = if (isSelected) Modifier.focusRequester(selected) else Modifier,
                                isSelected = isSelected,
                            )
                        }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize().focusRestorer(),
                        contentPadding = PaddingValues(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(currentSeason?.episodes ?: emptyList(), key = { _, episode -> episode.id }) { index, episode ->
                            TVRow(
                                onClick = { play(episode) },
                                modifier = if (index == 0) Modifier.focusRequester(firstEpisode) else Modifier,
                                minHeight = Theme.listRowHeight,
                            ) {
                                EpisodeRow(episode, app.watchHistory.entry("episode-${episode.id}")?.progress)
                            }
                        }
                    }
                    LoadStateView(state, emptyText = "No episodes listed") { scope.launch { load() } }
                }
            }
        }
    }
}

@Composable
private fun RowScope.EpisodeRow(episode: Episode, progress: Double?) {
    Txt(
        (episode.episodeNumber ?: 0).toString(),
        Modifier.width(28.dp), Type.meta, secondaryColor(), maxLines = 1, textAlign = TextAlign.End, tabular = true,
    )
    Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Txt(episode.title, style = Type.meta, maxLines = 1, weight = FontWeight.SemiBold)
        episode.plot?.let { Txt(it, style = Type.small, color = secondaryColor(), maxLines = 2) }
        if (progress != null) FocusAwareProgressBar(progress, Modifier.padding(top = 2.dp).widthIn(max = 160.dp).fillMaxWidth(), height = 4.dp)
    }
    episode.durationSeconds?.takeIf { it > 0 }?.let {
        Txt("${it / 60} min", Modifier.padding(start = 6.dp), Type.small, secondaryColor(), maxLines = 1)
    }
}
