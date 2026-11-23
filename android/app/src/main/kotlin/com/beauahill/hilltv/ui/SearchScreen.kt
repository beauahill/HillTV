package com.beauahill.hilltv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.beauahill.hilltv.core.CatalogKind
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.SearchIndex
import com.beauahill.hilltv.core.SeriesItem
import com.beauahill.hilltv.core.VODStream
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.model.CategoryVisibilityStore
import com.beauahill.hilltv.model.LoadState
import com.beauahill.hilltv.model.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search across channels, movies and series. Xtream has no search endpoint,
 * so the first visit downloads the three full catalogs once and indexes
 * them off the main thread. Each keystroke then scans the folded names.
 */
class SearchModel(val client: XtreamClient, private val visibility: CategoryVisibilityStore) {
    var query by mutableStateOf("")

    var catalogState: LoadState by mutableStateOf(LoadState.Idle)
        private set
    var channels: List<LiveStream> by mutableStateOf(emptyList())
        private set
    var movies: List<VODStream> by mutableStateOf(emptyList())
        private set
    var series: List<SeriesItem> by mutableStateOf(emptyList())
        private set

    /** Open detail pages. Empty means Search is at its root. */
    val path = mutableStateListOf<Route>()

    private var liveIndex: SearchIndex<LiveStream>? = null
    private var vodIndex: SearchIndex<VODStream>? = null
    private var seriesIndex: SearchIndex<SeriesItem>? = null

    val hasResults: Boolean get() = channels.isNotEmpty() || movies.isNotEmpty() || series.isNotEmpty()

    suspend fun loadCatalog() {
        if (catalogState != LoadState.Idle) return
        catalogState = LoadState.Loading
        try {
            val (live, vod, shows) = coroutineScope {
                val live = async { attempt { client.liveStreams() } }
                val vod = async { attempt { client.vodStreams() } }
                val shows = async { attempt { client.series() } }
                Triple(live.await(), vod.await(), shows.await())
            }
            val liveItems = live.getOrNull() ?: emptyList()
            val vodItems = vod.getOrNull() ?: emptyList()
            val seriesItems = shows.getOrNull() ?: emptyList()
            if (liveItems.isEmpty() && vodItems.isEmpty() && seriesItems.isEmpty()) {
                val error = live.exceptionOrNull()
                catalogState = if (error != null) LoadState.Failed(error.readable()) else LoadState.Empty
                return
            }
            val indexes = withContext(Dispatchers.Default) {
                Triple(
                    SearchIndex(liveItems) { it.name },
                    SearchIndex(vodItems) { it.name },
                    SearchIndex(seriesItems) { it.name },
                )
            }
            liveIndex = indexes.first
            vodIndex = indexes.second
            seriesIndex = indexes.third
            catalogState = LoadState.Loaded
        } catch (error: CancellationException) {
            // Leaving Search cancels the downloads; start over next visit.
            catalogState = LoadState.Idle
            throw error
        }
    }

    suspend fun retry() {
        catalogState = LoadState.Idle
        loadCatalog()
    }

    /**
     * Runs one search for the current query. The screen restarts it on every
     * keystroke, which cancels the one before, so only a pause in typing
     * reaches the index.
     */
    suspend fun search() {
        val text = query.trim()
        val live = liveIndex
        val vod = vodIndex
        val shows = seriesIndex
        if (text.isEmpty() || live == null || vod == null || shows == null) {
            channels = emptyList()
            movies = emptyList()
            series = emptyList()
            return
        }
        val hiding = visibility.visibility
        delay(250)
        val found = withContext(Dispatchers.Default) {
            // Hidden categories stay out of results, the same as everywhere else.
            Triple(
                live.search(text, SEARCH_DEPTH).filter { !hiding.isHidden(it.categoryID ?: "", CatalogKind.LIVE) }.take(RESULTS_PER_ROW),
                vod.search(text, SEARCH_DEPTH).filter { !hiding.isHidden(it.categoryID ?: "", CatalogKind.MOVIES) }.take(RESULTS_PER_ROW),
                shows.search(text, SEARCH_DEPTH).filter { !hiding.isHidden(it.categoryID ?: "", CatalogKind.SERIES) }.take(RESULTS_PER_ROW),
            )
        }
        channels = found.first
        movies = found.second
        series = found.third
    }

    private suspend fun <T> attempt(block: suspend () -> List<T>): Result<List<T>> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    private companion object {
        const val RESULTS_PER_ROW = 40

        /** Asked of the index before hidden categories are filtered out. */
        const val SEARCH_DEPTH = 400
    }
}

/** Full-screen search page. Back closes it. */
@Composable
fun SearchScreen(model: SearchModel) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val field = remember { TVTextFieldState() }
    LaunchedEffect(Unit) { field.focus() }

    LaunchedEffect(model) { model.loadCatalog() }
    LaunchedEffect(model.query, model.catalogState) { model.search() }

    DetailStack(model.path, model.client) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Theme.safeHorizontal + 12.dp, vertical = Theme.safeVertical)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Search")
                TVTextField(
                    model.query, { model.query = it }, "Channels, movies and series",
                    Modifier.widthIn(max = 520.dp), state = field, imeAction = ImeAction.Search,
                )
            }

            val trimmed = model.query.trim()
            when (val state = model.catalogState) {
                LoadState.Idle, LoadState.Loading -> Row(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spinner(size = 16.dp)
                    Txt("Loading the catalog for search. Big providers can take a moment.", style = Type.meta, color = Theme.muted)
                }
                is LoadState.Failed, LoadState.Empty -> LoadStateView(
                    state, Modifier.fillMaxWidth().height(150.dp), emptyText = "Nothing to search",
                ) { scope.launch { model.retry() } }
                LoadState.Loaded -> when {
                    trimmed.isEmpty() -> Hint("Search by name across live channels, movies and series. Press Select on the box to type.")
                    !model.hasResults -> Hint("No matches for \"$trimmed\".")
                }
            }

            if (model.channels.isNotEmpty()) {
                Rail("Channels") {
                    items(model.channels, key = { it.id }) { stream ->
                        TVCard(
                            onClick = { app.play(liveItem(stream, model.client, app)) },
                            onLongClick = {
                                val wasFavorite = app.favorites.contains(stream.id)
                                app.favorites.toggle(stream)
                                app.flash(if (wasFavorite) "Removed from Favorites" else "Added to Favorites")
                            },
                        ) { focused -> ChannelTile(stream, focused) }
                    }
                }
            }

            if (model.movies.isNotEmpty()) {
                Rail("Movies") {
                    items(model.movies, key = { it.id }) { movie ->
                        TVCard(onClick = { model.path.add(Route.Movie(movie)) }) { focused ->
                            PosterCard(movie.name, movie.posterURL, focused, width = 100.dp)
                        }
                    }
                }
            }

            if (model.series.isNotEmpty()) {
                Rail("Series") {
                    items(model.series, key = { it.id }) { item ->
                        TVCard(onClick = { model.path.add(Route.Series(item)) }) { focused ->
                            PosterCard(item.name, item.coverURL, focused, subtitle = item.seriesYear, width = 100.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Txt(text, Modifier.padding(horizontal = 20.dp), Type.meta, Theme.muted)
}
