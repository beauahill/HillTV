package com.beauahill.hilltv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import com.beauahill.hilltv.core.CatalogKind
import com.beauahill.hilltv.core.SeriesItem
import com.beauahill.hilltv.core.VODInfo
import com.beauahill.hilltv.core.VODStream
import com.beauahill.hilltv.core.XtreamCategory
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.model.CategoryVisibilityStore
import com.beauahill.hilltv.model.LoadState
import com.beauahill.hilltv.model.quietly
import com.beauahill.hilltv.model.readable
import com.beauahill.hilltv.player.PlayableItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A detail page opened from a grid or a rail. */
sealed interface Route {
    data class Movie(val movie: VODStream) : Route
    data class Series(val item: SeriesItem) : Route
}

/** "★ 7.1" */
internal fun ratingLabel(rating: Double): String = "★ %.1f".format(rating)

/**
 * Shows `root`, with the newest page in `path` over it. Back closes that
 * page. The root keeps its place underneath, so closing a movie returns to
 * the same poster.
 */
@Composable
fun DetailStack(path: SnapshotStateList<Route>, client: XtreamClient, root: @Composable () -> Unit) {
    val top = path.lastOrNull()
    Covered(
        covered = top != null,
        cover = {
            if (top != null) {
                key(top) {
                    BackHandler { if (path.isNotEmpty()) path.removeAt(path.lastIndex) }
                    when (top) {
                        is Route.Movie -> MovieDetailScreen(client, top.movie)
                        is Route.Series -> SeriesDetailScreen(client, top.item)
                    }
                }
            }
        },
        base = root,
    )
}

class MoviesModel(val client: XtreamClient, private val visibility: CategoryVisibilityStore) {
    var categories: List<XtreamCategory> by mutableStateOf(emptyList())
        private set
    var categoryState: LoadState by mutableStateOf(LoadState.Idle)
        private set
    var selectedCategoryID: String? by mutableStateOf(null)
        private set
    var movies: List<VODStream> by mutableStateOf(emptyList())
        private set
    var movieState: LoadState by mutableStateOf(LoadState.Idle)
        private set

    /** Open detail pages. Empty means Movies is at its root. */
    val path = mutableStateListOf<Route>()

    private val cache = HashMap<String, List<VODStream>>()

    /** Categories the user hasn't hidden. */
    val visibleCategories: List<XtreamCategory> get() = visibility.visible(categories, CatalogKind.MOVIES)

    val allHidden: Boolean get() = categories.isNotEmpty() && visibleCategories.isEmpty()

    suspend fun loadCategories() {
        if (categories.isNotEmpty()) {
            reconcileSelection()
            return
        }
        if (categoryState == LoadState.Loading) return
        categoryState = LoadState.Loading
        try {
            categories = client.vodCategories()
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
            if (movieState == LoadState.Idle) select(id)
        } else if (visible.isNotEmpty()) {
            select(visible.first().id)
        } else {
            selectedCategoryID = null
            movies = emptyList()
            movieState = LoadState.Idle
        }
    }

    suspend fun select(categoryID: String) {
        if (categoryID == selectedCategoryID && (movieState == LoadState.Loaded || movieState == LoadState.Loading)) return
        selectedCategoryID = categoryID
        cache[categoryID]?.let { cached ->
            movies = cached
            movieState = if (cached.isEmpty()) LoadState.Empty else LoadState.Loaded
            return
        }
        movies = emptyList()
        movieState = LoadState.Loading
        try {
            val result = client.vodStreams(categoryID)
            cache[categoryID] = result
            if (selectedCategoryID != categoryID) return
            movies = result
            movieState = if (result.isEmpty()) LoadState.Empty else LoadState.Loaded
        } catch (error: CancellationException) {
            if (selectedCategoryID == categoryID) movieState = LoadState.Idle
            throw error
        } catch (error: Exception) {
            if (selectedCategoryID == categoryID) movieState = LoadState.Failed(error.readable())
        }
    }

    suspend fun retry() {
        val id = selectedCategoryID ?: return
        movieState = LoadState.Idle
        select(id)
    }
}

/**
 * Selects a category a moment after focus lands on it, so up and down on the
 * remote browse without clicking. Returns what to call when focus lands.
 */
@Composable
internal fun rememberFocusSelect(select: suspend (String) -> Unit): (String) -> Unit {
    var pending by remember { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(select)
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        delay(350)
        current(id)
    }
    return { pending = it }
}

@Composable
fun MoviesScreen(model: MoviesModel) {
    val scope = rememberCoroutineScope()
    val focusSelect = rememberFocusSelect { model.select(it) }
    LaunchedEffect(model) { model.loadCategories() }

    DetailStack(model.path, model.client) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(180.dp).fillMaxHeight()) {
                CategoryColumn(
                    "Movies", model.visibleCategories, model.selectedCategoryID,
                    onFocus = { focusSelect(it.id) },
                    onSelect = { scope.launch { model.select(it.id) } },
                )
                LoadStateView(
                    if (model.allHidden) LoadState.Empty else model.categoryState,
                    emptyText = if (model.allHidden) ALL_CATEGORIES_HIDDEN_TEXT else "No movie categories",
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // A new grid per category, so each one opens at its top instead of
                // where the last one was scrolled to.
                key(model.selectedCategoryID) {
                    PosterGrid(model.movies, key = { it.id }, Modifier.fillMaxSize()) { movie ->
                        TVCard(onClick = { model.path.add(Route.Movie(movie)) }) { focused ->
                            PosterCard(movie.name, movie.posterURL, focused, subtitle = movie.rating?.let(::ratingLabel))
                        }
                    }
                }
                LoadStateView(model.movieState, emptyText = "No movies in this category") { scope.launch { model.retry() } }
            }
        }
    }
}

/** Pills that wrap onto new lines, like inline text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PillFlow(pills: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (pill in pills) Pill(pill)
    }
}

/**
 * Movie page: backdrop, poster, title, metadata pills, plot and Watch.
 * Opens immediately with what the list already knows, then fills in from
 * `get_vod_info`.
 */
@Composable
fun MovieDetailScreen(client: XtreamClient, movie: VODStream) {
    val app = LocalApp.current
    var info by remember { mutableStateOf<VODInfo?>(null) }
    val watchFocus = rememberInitialFocus()

    val title = info?.name ?: movie.name
    val poster = info?.posterURL ?: movie.posterURL
    val saved = app.watchHistory.entry("movie-${movie.id}")

    val pills = buildList {
        info?.year?.let { add(it) }
        info?.genre?.let { add(it) }
        info?.durationSeconds?.takeIf { it > 0 }?.let { add("${it / 60} min") }
        (info?.rating ?: movie.rating)?.takeIf { it > 0 }?.let { add(ratingLabel(it)) }
        add(movie.containerExtension.uppercase())
    }

    fun play(position: Double?) {
        app.play(PlayableItem.movie(movie, title, info?.year, poster, client, position))
    }

    LaunchedEffect(movie.id) {
        if (info == null) info = quietly { client.vodInfo(movie.id) }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(info?.backdropURL ?: poster, Modifier.bleed())
        Row(
            Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Box(Modifier.size(150.dp, 225.dp).clip(RoundedCornerShape(Theme.radius)).background(Theme.control)) {
                RemoteImage(poster, Modifier.fillMaxSize(), ContentScale.Crop)
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Movie")
                Txt(title, style = Type.title, color = Theme.text, maxLines = 2)
                PillFlow(pills)
                info?.plot?.let {
                    Txt(
                        it, Modifier.widthIn(max = 500.dp),
                        Type.meta.copy(lineHeight = 19.sp), Theme.text.copy(alpha = 0.9f), maxLines = 5,
                    )
                }

                Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // One button for both cases, so it keeps focus when "Watch now" turns
                    // into "Resume" on the way back from the player.
                    TVAction(onClick = { play(saved?.position) }, Modifier.initialFocus(watchFocus), prominent = true) {
                        Icon(Icons.Filled.PlayArrow, size = 14.dp)
                        Txt(if (saved != null) "Resume from ${clockString(saved.position)}" else "Watch now", maxLines = 1)
                    }
                    if (saved != null) {
                        TVAction(onClick = { play(null) }) {
                            Icon(Icons.Filled.Replay, size = 14.dp)
                            Txt("Start over", maxLines = 1)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    info?.director?.let { Credit("Director", it) }
                    info?.cast?.let { Credit("Cast", it) }
                }
            }
        }
    }
}

@Composable
private fun Credit(label: String, value: String) {
    BasicText(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Theme.muted)) { append("$label  ") }
            withStyle(SpanStyle(color = Theme.text)) { append(value) }
        },
        Modifier.widthIn(max = 500.dp),
        Type.small,
        overflow = ellipsis,
        maxLines = 2,
    )
}
