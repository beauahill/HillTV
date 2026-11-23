package com.beauahill.hilltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.beauahill.hilltv.core.CatalogKind
import com.beauahill.hilltv.core.CategoryVisibility
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.SeriesItem
import com.beauahill.hilltv.core.VODInfo
import com.beauahill.hilltv.core.VODStream
import com.beauahill.hilltv.core.XtreamCategory
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.model.CategoryVisibilityStore
import com.beauahill.hilltv.model.EPGStore
import com.beauahill.hilltv.model.LoadState
import com.beauahill.hilltv.model.quietly
import com.beauahill.hilltv.model.readable
import com.beauahill.hilltv.player.PlayableItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Home: search and the time, a featured movie, what's live on favorite
 * channels, continue watching, then rails for the first few movie and
 * series categories. Uses per-category requests rather than the full
 * catalog, which can be tens of megabytes on big panels.
 */
class HomeModel(val client: XtreamClient, private val visibility: CategoryVisibilityStore) {
    class RailData<Item>(val category: XtreamCategory, val items: List<Item>)

    var movieRails: List<RailData<VODStream>> by mutableStateOf(emptyList())
        private set
    var seriesRails: List<RailData<SeriesItem>> by mutableStateOf(emptyList())
        private set
    var featured: VODStream? by mutableStateOf(null)
        private set
    var featuredInfo: VODInfo? by mutableStateOf(null)
        private set
    var state: LoadState by mutableStateOf(LoadState.Idle)
        private set

    /** Open detail pages. Empty means Home is at its root. */
    val path = mutableStateListOf<Route>()

    /** What was hidden when the rails were built. A change means rebuild. */
    private var loadedWith: CategoryVisibility? = null

    suspend fun load() {
        // Categories hidden or shown in Settings since the last visit: build the rails again.
        if ((state == LoadState.Loaded || state == LoadState.Empty) && loadedWith != visibility.visibility) {
            state = LoadState.Idle
        }
        if (state != LoadState.Idle) return
        state = LoadState.Loading
        val hiding = visibility.visibility

        try {
            // A failed or cancelled load must end somewhere retryable, never stuck.
            var firstError: Exception? = null
            var movieCats = emptyList<XtreamCategory>()
            var seriesCats = emptyList<XtreamCategory>()
            try {
                movieCats = client.vodCategories()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                firstError = error
            }
            try {
                seriesCats = client.seriesCategories()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                firstError = firstError ?: error
            }
            if (movieCats.isEmpty() && seriesCats.isEmpty() && firstError != null) {
                state = LoadState.Failed(firstError.readable())
                return
            }
            movieCats = hiding.visible(movieCats, CatalogKind.MOVIES)
            seriesCats = hiding.visible(seriesCats, CatalogKind.SERIES)

            val (loadedMovies, loadedSeries) = coroutineScope {
                val movies = async { rails(movieCats.take(RAIL_COUNT)) { client.vodStreams(it) } }
                val series = async { rails(seriesCats.take(RAIL_COUNT)) { client.series(it) } }
                movies.await() to series.await()
            }
            movieRails = loadedMovies
            seriesRails = loadedSeries
            loadedWith = hiding
            state = if (movieRails.isEmpty() && seriesRails.isEmpty()) LoadState.Empty else LoadState.Loaded
        } catch (error: CancellationException) {
            state = LoadState.Idle
            throw error
        }

        featured = movieRails.asSequence().flatMap { it.items }.firstOrNull { it.posterURL != null }
        featured?.let { movie -> featuredInfo = quietly { client.vodInfo(movie.id) } }
    }

    suspend fun retry() {
        state = LoadState.Idle
        load()
    }

    /** One rail per category, in order. Categories that fail or are empty are left out. */
    private suspend fun <Item> rails(categories: List<XtreamCategory>, fetch: suspend (String) -> List<Item>): List<RailData<Item>> =
        coroutineScope {
            categories.map { category ->
                async {
                    val items = quietly { fetch(category.id) } ?: emptyList()
                    if (items.isEmpty()) null else RailData(category, items.take(ITEMS_PER_RAIL))
                }
            }.awaitAll().filterNotNull()
        }

    private companion object {
        const val RAIL_COUNT = 3
        const val ITEMS_PER_RAIL = 20
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d, ")

/** A live channel as the player takes it. */
internal fun liveItem(stream: LiveStream, client: XtreamClient, app: com.beauahill.hilltv.model.AppModel, subtitle: String? = null) =
    PlayableItem(
        id = "live-${stream.id}",
        title = stream.name,
        subtitle = subtitle,
        url = client.liveURL(stream.id, app.liveFormat),
        isLive = true,
    )

@Composable
fun HomeScreen(model: HomeModel, epg: EPGStore, openSearch: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.load() }

    DetailStack(model.path, model.client) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Search and the time.
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TVChip(onClick = openSearch) {
                    Icon(Icons.Outlined.Search, size = 13.dp)
                    Txt("Search channels, movies and series", maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Txt(
                    epg.now.atZone(ZoneId.systemDefault()).format(dateFormat) + epg.now.shortTime,
                    style = Type.small, color = Theme.muted, maxLines = 1,
                )
            }

            Hero(model)

            val favorites = app.favorites.channels
            if (favorites.isNotEmpty()) {
                Rail("Live now on your favorites") {
                    items(favorites, key = { it.id }) { stream ->
                        DisposableEffect(stream.id) {
                            epg.rowAppeared(stream.id)
                            onDispose { epg.rowDisappeared(stream.id) }
                        }
                        TVCard(onClick = { app.play(liveItem(stream, model.client, app)) }) { focused ->
                            LiveNowCard(stream, epg, focused)
                        }
                    }
                }
            }

            val history = app.watchHistory.entries
            if (history.isNotEmpty()) {
                Rail("Continue watching") {
                    items(history, key = { it.id }) { entry ->
                        TVCard(
                            onClick = { PlayableItem.resuming(entry, model.client)?.let(app::play) },
                            onLongClick = {
                                app.watchHistory.remove(entry.id)
                                app.flash("Removed from Continue Watching")
                            },
                        ) { focused ->
                            PosterCard(entry.title, entry.artworkURL, focused, subtitle = entry.subtitle, width = 100.dp, progress = entry.progress)
                        }
                    }
                }
            }

            for (rail in model.movieRails) {
                Rail("Movies · ${rail.category.name}") {
                    items(rail.items, key = { it.id }) { movie ->
                        TVCard(onClick = { model.path.add(Route.Movie(movie)) }) { focused ->
                            PosterCard(movie.name, movie.posterURL, focused, width = 100.dp)
                        }
                    }
                }
            }

            for (rail in model.seriesRails) {
                Rail("Series · ${rail.category.name}") {
                    items(rail.items, key = { it.id }) { item ->
                        TVCard(onClick = { model.path.add(Route.Series(item)) }) { focused ->
                            PosterCard(item.name, item.coverURL, focused, subtitle = item.seriesYear, width = 100.dp)
                        }
                    }
                }
            }

            if (model.state != LoadState.Loaded) {
                LoadStateView(model.state, Modifier.fillMaxWidth().height(150.dp), emptyText = "Nothing to show yet") {
                    scope.launch { model.retry() }
                }
            }
        }
    }
}

@Composable
private fun Hero(model: HomeModel) {
    val app = LocalApp.current
    val movie = model.featured
    if (movie == null) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("Home")
            Wordmark(38.sp)
        }
        return
    }
    val info = model.featuredInfo
    val saved = app.watchHistory.entry("movie-${movie.id}")
    val meta = listOfNotNull(
        info?.year,
        info?.genre,
        info?.durationSeconds?.takeIf { it > 0 }?.let { "${it / 60} min" },
        (info?.rating ?: movie.rating)?.takeIf { it > 0 }?.let(::ratingLabel),
    ).joinToString(" · ")

    Box(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .heightIn(min = 170.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Theme.surface),
        contentAlignment = Alignment.BottomStart,
    ) {
        // Unblurred artwork on the right of the card, faded into the surface on the left.
        (info?.backdropURL ?: movie.posterURL)?.let {
            AsyncImage(it, null, Modifier.matchParentSize().alpha(0.8f), contentScale = ContentScale.Crop)
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to Theme.background,
                    0.4f to Theme.background.copy(alpha = 0.75f),
                    0.85f to Color.Transparent,
                )
            )
        )
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Eyebrow("Featured movie")
            Txt(info?.name ?: movie.name, style = Type.display, color = Theme.text, maxLines = 1)
            if (meta.isNotEmpty()) Txt(meta, style = Type.meta, color = Theme.muted, maxLines = 1)
            Row(Modifier.padding(top = 1.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                TVAction(
                    onClick = {
                        app.play(
                            PlayableItem.movie(
                                movie, info?.name ?: movie.name, info?.year,
                                info?.posterURL ?: movie.posterURL, model.client, saved?.position,
                            )
                        )
                    },
                    prominent = true,
                ) {
                    Icon(Icons.Filled.PlayArrow, size = 14.dp)
                    Txt(if (saved == null) "Play" else "Resume", maxLines = 1)
                }
                TVAction("Details", onClick = { model.path.add(Route.Movie(movie)) })
            }
        }
    }
}
