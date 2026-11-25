package com.beauahill.hilltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.beauahill.hilltv.core.CatalogKind
import com.beauahill.hilltv.core.CategoryVisibility
import com.beauahill.hilltv.core.EPGSchedule
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.SeriesItem
import com.beauahill.hilltv.core.VODInfo
import com.beauahill.hilltv.core.VODStream
import com.beauahill.hilltv.core.XtreamCategory
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.core.displayName
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Something Home can show in its billboard. */
sealed interface BillboardItem {
    data class Live(val stream: LiveStream) : BillboardItem

    /**
     * A continue-watching entry, by its ID. The entry itself changes every
     * few seconds of playback, so it is read from the store when needed.
     */
    data class Resume(val id: String) : BillboardItem
    data class Movie(val movie: VODStream) : BillboardItem
    data class Series(val series: SeriesItem) : BillboardItem
}

/**
 * Home's data: rails for the first few movie and series categories, and
 * the details of whichever movie the billboard shows. Uses per-category
 * requests rather than the full catalog, which can be tens of megabytes on
 * big panels.
 */
class HomeModel(val client: XtreamClient, private val visibility: CategoryVisibilityStore) {
    class RailData<Item>(val category: XtreamCategory, val items: List<Item>)

    var movieRails: List<RailData<VODStream>> by mutableStateOf(emptyList())
        private set
    var seriesRails: List<RailData<SeriesItem>> by mutableStateOf(emptyList())
        private set
    var state: LoadState by mutableStateOf(LoadState.Idle)
        private set

    /** Movie details fetched for the billboard, by stream ID. */
    val movieInfo = mutableStateMapOf<Int, VODInfo>()

    /** Movies whose details were asked for and didn't come. */
    private val missingInfo = HashSet<Int>()

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
    }

    /**
     * Fetches a movie's plot and backdrop once. Called after focus has
     * rested on the movie, so passing over a row doesn't ask for each one.
     */
    suspend fun loadInfo(movie: VODStream) {
        if (movieInfo[movie.id] != null || movie.id in missingInfo) return
        // Leaving the movie cancels the request; that isn't "no details".
        val info = quietly { client.vodInfo(movie.id) }
        if (info != null) movieInfo[movie.id] = info else missingInfo.add(movie.id)
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

/** A live channel as the player takes it. */
internal fun liveItem(stream: LiveStream, client: XtreamClient, app: com.beauahill.hilltv.model.AppModel, subtitle: String? = null) =
    PlayableItem(
        id = "live-${stream.id}",
        title = stream.displayName.title,
        subtitle = subtitle,
        url = client.liveURL(stream.id, app.liveFormat),
        isLive = true,
    )

private val posterWidth = 85.dp
private val billboardHeight = 210.dp

/**
 * Home: a billboard on top that shows whatever has focus, live or on
 * demand, and rows underneath that mix both. Live channels first, then
 * continue watching, then movies and series.
 *
 * Live channels have no picture here, only the provider's logo and the
 * guide's text: a picture would mean a connection per channel.
 */
@Composable
fun HomeScreen(model: HomeModel, epg: EPGStore) {
    val app = LocalApp.current
    val menus = LocalHoldMenu.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.load() }

    /** The tile that has focus now, or had it last. */
    var focused by remember { mutableStateOf<BillboardItem?>(null) }
    /** What the billboard shows: the last tile focus rested on. */
    var shown by remember { mutableStateOf<BillboardItem?>(null) }

    val favorites = app.favorites.channels
    val history = app.watchHistory.entries

    // A finished or removed continue-watching entry leaves the billboard.
    // Before anything has had focus, lead with the first thing on the page.
    val billboardItem: BillboardItem? = shown?.takeIf { it !is BillboardItem.Resume || app.watchHistory.entry(it.id) != null }
        ?: favorites.firstOrNull()?.let { BillboardItem.Live(it) }
        ?: history.firstOrNull()?.let { BillboardItem.Resume(it.id) }
        ?: model.movieRails.firstOrNull()?.items?.firstOrNull()?.let { BillboardItem.Movie(it) }
        ?: model.seriesRails.firstOrNull()?.items?.firstOrNull()?.let { BillboardItem.Series(it) }

    /**
     * A tile taking focus starts the wait; a tile losing it, to the
     * billboard's buttons or the rail, cancels it. Passing through a row
     * on the way up must not change the billboard, as on the Apple TV.
     */
    fun track(item: BillboardItem, gained: Boolean) {
        if (gained) focused = item else if (focused == item) focused = null
    }

    // Restarted on every focus change, which cancels the run before it, so
    // the billboard only changes once focus stays put.
    LaunchedEffect(focused) {
        val item = focused ?: return@LaunchedEffect
        if (item == shown) return@LaunchedEffect
        delay(250)
        shown = item
    }

    LaunchedEffect(billboardItem) {
        val movie = (billboardItem as? BillboardItem.Movie)?.movie ?: return@LaunchedEffect
        delay(400)
        model.loadInfo(movie)
    }

    DetailStack(model.path, model.client) {
        Column(Modifier.fillMaxSize()) {
            Billboard(billboardItem, model, epg)

            // Clipped, so the rows scroll under the billboard and never over it.
            Column(
                Modifier.weight(1f).fillMaxWidth().clipToBounds().verticalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (favorites.isNotEmpty()) {
                    Rail("Live now on your favorites") {
                        items(favorites, key = { it.id }) { stream ->
                            DisposableEffect(stream.id) {
                                epg.rowAppeared(stream.id)
                                onDispose { epg.rowDisappeared(stream.id) }
                            }
                            TVCard(
                                onClick = { app.play(liveItem(stream, model.client, app)) },
                                onLongClick = { menus.show(stream.displayName.title, listOf(multiviewAction(app, stream))) },
                                onFocusChanged = { track(BillboardItem.Live(stream), it) },
                            ) { cardFocused ->
                                LiveNowCard(stream, epg, cardFocused)
                            }
                        }
                    }
                }

                if (history.isNotEmpty()) {
                    Rail("Continue watching") {
                        items(history, key = { it.id }) { entry ->
                            TVCard(
                                onClick = { PlayableItem.resuming(entry, model.client)?.let(app::play) },
                                onLongClick = {
                                    menus.show(
                                        entry.title,
                                        listOf(MenuAction("Remove from Continue Watching") { app.watchHistory.remove(entry.id) }),
                                        eyebrow = "Continue watching",
                                    )
                                },
                                onFocusChanged = { track(BillboardItem.Resume(entry.id), it) },
                            ) { cardFocused ->
                                PosterCard(entry.title, entry.artworkURL, cardFocused, subtitle = entry.subtitle, width = posterWidth, progress = entry.progress)
                            }
                        }
                    }
                }

                // Movie and series rails, taking turns.
                for (index in 0 until maxOf(model.movieRails.size, model.seriesRails.size)) {
                    model.movieRails.getOrNull(index)?.let { rail ->
                        Rail("Movies · ${rail.category.displayName.line}") {
                            items(rail.items, key = { it.id }) { movie ->
                                TVCard(
                                    onClick = { model.path.add(Route.Movie(movie)) },
                                    onFocusChanged = { track(BillboardItem.Movie(movie), it) },
                                ) { cardFocused ->
                                    PosterCard(movie.name, movie.posterURL, cardFocused, width = posterWidth)
                                }
                            }
                        }
                    }
                    model.seriesRails.getOrNull(index)?.let { rail ->
                        Rail("Series · ${rail.category.displayName.line}") {
                            items(rail.items, key = { it.id }) { item ->
                                TVCard(
                                    onClick = { model.path.add(Route.Series(item)) },
                                    onFocusChanged = { track(BillboardItem.Series(item), it) },
                                ) { cardFocused ->
                                    PosterCard(item.name, item.coverURL, cardFocused, subtitle = item.seriesYear, width = posterWidth)
                                }
                            }
                        }
                    }
                }

                if (model.state != LoadState.Loaded) {
                    LoadStateView(model.state, Modifier.fillMaxWidth().height(120.dp), emptyText = "Nothing to show yet") {
                        scope.launch { model.retry() }
                    }
                }
            }
        }
    }
}

// MARK: Billboard

private class BillboardContent(
    val eyebrow: String,
    val title: String,
    val meta: String = "",
    val detail: String? = null,
    val artURL: String? = null,
    /** A red dot and red text, for a live channel. */
    val isLive: Boolean = false,
)

/**
 * The top of Home. Its height is fixed and blank lines hold the text rows,
 * so the rows below don't jump as focus moves between items with more or
 * less to say.
 */
@Composable
private fun Billboard(item: BillboardItem?, model: HomeModel, epg: EPGStore) {
    val app = LocalApp.current
    if (item == null) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("Home")
            Wordmark(38.sp)
        }
        return
    }

    val content = when (item) {
        is BillboardItem.Live -> {
            val now = epg.now
            val schedule = epg.schedule(item.stream.id)
            val current = schedule?.current(now)
            val channel = item.stream.displayName.title
            BillboardContent(
                eyebrow = "Live · $channel",
                title = current?.title ?: channel,
                meta = when {
                    current != null ->
                        "${current.start.shortTime} to ${current.end.shortTime} · ${remainingLabel(EPGSchedule.minutesLeft(current, now))}"
                    schedule != null -> "No guide data"
                    else -> ""
                },
                detail = schedule?.next(now)?.let { "Next: ${it.title}, ${it.start.shortTime}" },
                isLive = true,
            )
        }
        is BillboardItem.Resume -> {
            val entry = app.watchHistory.entry(item.id)
            if (entry == null) {
                BillboardContent("Continue watching", " ")
            } else {
                val duration = entry.duration
                BillboardContent(
                    eyebrow = "Continue watching",
                    title = entry.title,
                    meta = listOfNotNull(
                        entry.subtitle,
                        duration?.takeIf { it > entry.position }?.let { remainingLabel(maxOf(1, ((it - entry.position) / 60).toInt())) },
                    ).joinToString(" · "),
                    detail = "Stopped at ${clockString(entry.position)}.",
                    artURL = entry.artworkURL,
                )
            }
        }
        is BillboardItem.Movie -> {
            val movie = item.movie
            val info = model.movieInfo[movie.id]
            BillboardContent(
                eyebrow = "Movie",
                title = info?.name ?: movie.name,
                meta = listOfNotNull(
                    info?.year,
                    info?.genre,
                    info?.durationSeconds?.takeIf { it > 0 }?.let { "${it / 60} min" },
                    (info?.rating ?: movie.rating)?.takeIf { it > 0 }?.let(::ratingLabel),
                ).joinToString(" · "),
                detail = info?.plot,
                artURL = info?.backdropURL ?: movie.posterURL,
            )
        }
        is BillboardItem.Series -> {
            val series = item.series
            BillboardContent(
                eyebrow = "Series",
                title = series.name,
                meta = listOfNotNull(
                    series.seriesYear,
                    series.genre?.takeIf { it.isNotEmpty() },
                    series.rating?.takeIf { it > 0 }?.let(::ratingLabel),
                ).joinToString(" · "),
                detail = series.plot,
                artURL = series.coverURL,
            )
        }
    }

    Box(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 6.dp)
            .fillMaxWidth()
            .height(billboardHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Theme.surface),
    ) {
        HeroArt(content.artURL)
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (content.isLive) Box(Modifier.size(6.dp).background(Theme.live, RoundedCornerShape(3.dp)))
                    Eyebrow(content.eyebrow, color = if (content.isLive) Theme.live else Theme.accent)
                }
                Txt(content.title, style = Type.display, color = Theme.text, maxLines = 1)
                Txt(content.meta.ifEmpty { " " }, style = Type.meta, color = Theme.muted, maxLines = 1)
                Txt(
                    content.detail?.takeIf { it.isNotBlank() } ?: " ",
                    Modifier.widthIn(max = 450.dp),
                    Type.small, Theme.text.copy(alpha = 0.85f), maxLines = 2, minLines = 2,
                )
                BillboardButtons(item, model, Modifier.padding(top = 2.dp))
            }
            if (item is BillboardItem.Live) ChannelLogo(item.stream.iconURL, width = 180.dp, height = 110.dp)
        }
    }
}

/**
 * The billboard's buttons act on what it shows. The first button is one
 * control whose label changes, not a different button per kind of item:
 * after the player closes, focus goes back to the control it left, and that
 * only works if the control is still the same one.
 */
@Composable
private fun BillboardButtons(item: BillboardItem, model: HomeModel, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val primary: String
    val play: () -> Unit
    var secondary: String? = null
    var other: () -> Unit = {}

    when (item) {
        is BillboardItem.Live -> {
            primary = "Watch live"
            play = { app.play(liveItem(item.stream, model.client, app)) }
            secondary = if (app.multiview.tiles.contains(item.stream.id)) "Open Multiview" else "Add to Multiview"
            other = { app.multiview.addAndOpen(item.stream, app.multiviewLimit) }
        }
        is BillboardItem.Resume -> {
            primary = "Resume"
            // The entry as it is now, not as it was when focus was on it.
            play = { app.watchHistory.entry(item.id)?.let { PlayableItem.resuming(it, model.client) }?.let(app::play) }
        }
        is BillboardItem.Movie -> {
            val movie = item.movie
            val saved = app.watchHistory.entry("movie-${movie.id}")
            primary = if (saved == null) "Play" else "Resume"
            play = {
                val info = model.movieInfo[movie.id]
                app.play(
                    PlayableItem.movie(
                        movie, info?.name ?: movie.name, info?.year,
                        info?.posterURL ?: movie.posterURL, model.client, saved?.position,
                    )
                )
            }
            secondary = "Details"
            other = { model.path.add(Route.Movie(movie)) }
        }
        is BillboardItem.Series -> {
            primary = "Episodes"
            play = { model.path.add(Route.Series(item.series)) }
        }
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        TVAction(onClick = play, prominent = true) {
            if (item !is BillboardItem.Series) Icon(Icons.Filled.PlayArrow, size = 14.dp)
            Txt(primary, maxLines = 1)
        }
        secondary?.let { TVAction(it, onClick = other) }
    }
}

/** Unblurred artwork on the right of the billboard, faded into the surface on the left. */
@Composable
private fun BoxScope.HeroArt(url: String?) {
    // A new image per address. Without this the last picture stays up when
    // the address changes to none, as it does when the billboard moves from
    // a movie to a live channel.
    key(url) {
        if (!url.isNullOrBlank()) {
            AsyncImage(url, null, Modifier.matchParentSize().alpha(0.8f), contentScale = ContentScale.Crop)
        }
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
}
