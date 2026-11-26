package com.beauahill.hilltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SportsBasketball
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.beauahill.hilltv.core.CategoryGroup
import com.beauahill.hilltv.core.EPGEntry
import com.beauahill.hilltv.core.EPGSchedule
import com.beauahill.hilltv.core.LiveFormat
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.Scoreboard
import com.beauahill.hilltv.core.SportsGame
import com.beauahill.hilltv.core.XtreamCategory
import com.beauahill.hilltv.core.displayName
import com.beauahill.hilltv.model.EPGStore
import com.beauahill.hilltv.model.LivePreviewMode
import com.beauahill.hilltv.model.LiveTVModel
import com.beauahill.hilltv.model.LiveTVModel.Pill
import com.beauahill.hilltv.model.LoadState
import com.beauahill.hilltv.model.ScoreboardStore
import com.beauahill.hilltv.player.LivePreview
import com.beauahill.hilltv.player.PlayableItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** What has focus in the list: a channel row or a game row. */
private sealed interface ListFocus {
    data class Stream(val id: Int) : ListFocus
    data class Game(val id: String) : ListFocus
}

/**
 * Live TV: big pills for Favorites and the category groups, the selected
 * group's own categories as a smaller row, the channel list on the left
 * with what's on now in every row, and details for the focused channel on
 * the right. The model is in `model/LiveTVModel.kt`.
 *
 * In Sports, "Games" lists games from the scores feed above the sports
 * channels. A focused game puts a scoreboard in the detail pane.
 */
@Composable
fun LiveTVScreen(model: LiveTVModel, epg: EPGStore, scores: ScoreboardStore) {
    val app = LocalApp.current
    val menus = LocalHoldMenu.current
    val scope = rememberCoroutineScope()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inForeground = lifecycle.isAtLeast(Lifecycle.State.RESUMED)

    var showingCategories by remember { mutableStateOf(false) }
    var listFocus by remember { mutableStateOf<ListFocus?>(null) }
    var playJob by remember { mutableStateOf<Job?>(null) }
    /** Channel whose preview is playing, once focus has rested on it. */
    var previewStreamID by remember { mutableStateOf<Int?>(null) }
    /** Channels whose preview failed this visit. They keep the still. */
    var failedPreviews by remember { mutableStateOf(emptySet<Int>()) }
    /** The game the detail pane shows: the last game row that held focus. */
    var detailGameID by remember { mutableStateOf<String?>(null) }
    /** Set to ask which channel to watch a game on. */
    var picking by remember { mutableStateOf<GamePick?>(null) }
    /** Set to move a channel within the list on screen. */
    var moving by remember { mutableStateOf<ChannelMove?>(null) }

    val nowPlaying = app.nowPlaying
    val streams = if (model.showsFavorites) app.favorites.channels else model.streams
    val startWithFavorites = app.favorites.channels.isNotEmpty()

    val listState: LoadState = when {
        model.showsFavorites -> if (app.favorites.channels.isEmpty()) LoadState.Empty else LoadState.Loaded
        model.categories.isEmpty() -> model.categoryState
        // Games come from the scores feed. A slow or failed channel fetch
        // shouldn't cover them with a spinner or an error.
        model.showsGames -> LoadState.Loaded
        else -> model.streamState
    }

    val emptyText = when {
        model.showsFavorites ->
            if (model.allHidden) ALL_CATEGORIES_HIDDEN_TEXT else "No favorites yet. Hold Select on a channel to add it."
        model.categories.isEmpty() -> "No live categories"
        else -> "No channels in this category"
    }

    val detailStream = model.detailStreamID?.let { id -> streams.firstOrNull { it.id == id } } ?: streams.firstOrNull()

    // MARK: Games

    /** Live games, then ones starting soon, then ones just finished. */
    val games = if (model.showsGames) Scoreboard.sections(scores.games, epg.now).all else emptyList()

    val detailGame: SportsGame? = if (!model.showsGames) {
        null
    } else {
        detailGameID?.let { id -> games.firstOrNull { it.id == id } }
            // Nothing has had focus yet: lead with the first game, not a channel.
            ?: if (model.detailStreamID == null) games.firstOrNull() else null
    }

    /** The scores feed is only polled while games can be seen. */
    val gamesActive = model.showsGames && nowPlaying == null && picking == null && !app.multiview.isShowing && inForeground

    /** False while the sports channels are still loading or have not been matched to the games yet. */
    val channelsMatched = model.sportsChannels.isNotEmpty() && scores.hasMatched

    // MARK: Preview

    /**
     * The preview holds a connection, so it only runs while it can be seen:
     * not behind the player, the multiview, a menu or another full-screen
     * list, not in the background. This page stays alive under all of those.
     */
    val previewAllowed = app.previewMode != LivePreviewMode.OFF && app.previewSupported &&
        nowPlaying == null && !showingCategories && picking == null && moving == null &&
        !menus.isShowing && !app.multiview.isShowing && inForeground

    val previewURL = previewStreamID
        ?.takeIf { previewAllowed && it == detailStream?.id }
        ?.let { model.client.liveURL(it, LiveFormat.HLS) }

    fun play(stream: LiveStream) {
        val current = epg.schedule(stream.id)?.current(Instant.now())
        val item = PlayableItem(
            id = "live-${stream.id}",
            title = stream.displayName.title,
            subtitle = current?.title,
            url = model.client.liveURL(stream.id, app.liveFormat),
            isLive = true,
        )
        playJob?.cancel()
        if (previewStreamID == null || app.hasSpareConnection) {
            app.play(item)
            return
        }
        // One connection and a preview holding it: let go first, then open the player.
        previewStreamID = null
        playJob = scope.launch {
            delay(700)
            app.play(item)
        }
    }

    /** Tunes straight in only when exactly one channel names both teams. Anything less sure asks first. */
    fun open(game: SportsGame) {
        val matches = scores.matches[game.id] ?: emptyList()
        val sure = matches.filter { it.strength == com.beauahill.hilltv.core.GameChannelMatch.Strength.TEAMS }
        if (sure.size == 1) {
            play(sure.first().stream)
        } else if (matches.isNotEmpty()) {
            picking = GamePick(game, matches.take(24))
        }
    }

    /**
     * Hold Select on a game: the same rule as opening it. One channel that
     * names both teams goes straight into the multiview; anything less sure
     * asks which channel first.
     */
    fun addToMultiview(game: SportsGame) {
        val matches = scores.matches[game.id] ?: emptyList()
        val sure = matches.filter { it.strength == com.beauahill.hilltv.core.GameChannelMatch.Strength.TEAMS }
        if (sure.size == 1) {
            app.multiview.addAndOpen(sure.first().stream, app.multiviewLimit)
        } else if (matches.isNotEmpty()) {
            picking = GamePick(game, matches.take(24), forMultiview = true)
        }
    }

    /**
     * Favorites and single categories can be put in the viewer's own order.
     * "All" and Games are built from several categories and can't.
     */
    val canReorder = (model.showsFavorites || model.reorderCategoryID != null) && streams.size > 1

    val listTitle = if (model.showsFavorites) {
        "Favorites"
    } else {
        model.categories.firstOrNull { it.id == model.reorderCategoryID }?.displayName?.title ?: "Live TV"
    }

    fun openMenu(stream: LiveStream) {
        menus.show(
            stream.displayName.title,
            buildList {
                add(favoriteAction(app, stream))
                add(multiviewAction(app, stream))
                if (canReorder) add(MenuAction("Move") { moving = ChannelMove(listTitle, streams, stream) })
                if (model.hasCustomOrder) add(MenuAction("Use the provider's order") { model.resetOrder() })
            },
        )
    }

    LaunchedEffect(model) { model.loadCategories(startWithFavorites) }

    // A new selection starts at the top. So do games arriving after the
    // channels: a list keeps its place when rows are added above, which
    // would leave the games out of sight over the top edge.
    val channelList = rememberLazyListState()
    val chipRow = remember { FocusRequester() }
    LaunchedEffect(model.selection, games.isEmpty()) {
        if (listFocus == null) channelList.scrollToItem(0)
    }

    // Restarted whenever the channel or `previewAllowed` changes, which
    // cancels the previous run. Stops the old preview at once, starts the new
    // one after a pause, and gives up the connection if the same channel sits
    // there for a few minutes.
    LaunchedEffect(model.detailStreamID, previewAllowed) {
        previewStreamID = null
        val id = model.detailStreamID
        if (!previewAllowed || id == null || id in failedPreviews) return@LaunchedEffect
        delay(1_000)
        previewStreamID = id
        delay(180_000)
        previewStreamID = null
    }

    LaunchedEffect(gamesActive) {
        if (!gamesActive) return@LaunchedEffect
        while (true) {
            scores.refresh()
            scores.rematch(Scoreboard.sections(scores.games, epg.now).all, model.sportsChannels, epg.schedules.toMap())
            delay(60_000)
        }
    }

    // The sports channels arrive after the first scores: match again.
    LaunchedEffect(model.sportsChannels) {
        if (model.sportsChannels.isNotEmpty()) {
            scores.rematch(Scoreboard.sections(scores.games, epg.now).all, model.sportsChannels, epg.schedules.toMap())
        }
    }

    // Waits for focus to settle before updating the detail pane, so holding
    // down on the remote doesn't redraw it for every row passed.
    LaunchedEffect(listFocus) {
        val focus = listFocus ?: return@LaunchedEffect
        delay(250)
        when (focus) {
            is ListFocus.Stream -> {
                detailGameID = null
                model.showDetail(focus.id)
                epg.prioritize(focus.id)
            }
            is ListFocus.Game -> {
                // Clearing the channel also stops any preview.
                detailGameID = focus.id
                model.showDetail(null)
            }
        }
    }

    FullScreenCover(visible = showingCategories, onDismiss = { showingCategories = false }) {
        CategoryPicker(
            title = model.selection?.let { pillTitle(it.pill) } ?: "Live TV",
            categories = model.subcategories,
            selectedID = model.selection?.sub,
        ) { id ->
            showingCategories = false
            scope.launch { model.selectSub(id) }
        }
    }

    picking?.let { pick ->
        FullScreenCover(visible = true, onDismiss = { picking = null }) {
            GameChannelPicker(pick) { stream ->
                picking = null
                if (pick.forMultiview) app.multiview.addAndOpen(stream, app.multiviewLimit) else play(stream)
            }
        }
    }

    MoveChannelCover(moving, onClose = { moving = null }) { move, index ->
        if (model.showsFavorites) app.favorites.move(move.stream.id, index) else model.move(move.stream.id, index)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val hasChips = model.subcategories.isNotEmpty()
        PillBar(model, down = if (hasChips) chipRow else null) { pill -> scope.launch { model.select(pill) } }

        if (hasChips) {
            CategoryBar(model, chipRow, openGrid = { showingCategories = true }) { sub -> scope.launch { model.selectSub(sub) } }
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(380.dp).fillMaxHeight()) {
                LazyColumn(
                    Modifier.fillMaxSize().focusRestorer(),
                    state = channelList,
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (model.showsGames) {
                        // Games from the scores feed, then a heading for the channels below.
                        // With no scores this is nothing at all, and Sports is plain channels.
                        items(games, key = { "game-${it.id}" }) { game ->
                            TVRow(
                                onClick = { open(game) },
                                minHeight = 64.dp,
                                // Only once a channel has been found for the game.
                                onLongClick = {
                                    if (!scores.matches[game.id].isNullOrEmpty()) {
                                        menus.show(
                                            "${game.away.shortName} at ${game.home.shortName}",
                                            listOf(MenuAction("Add to Multiview") { addToMultiview(game) }),
                                            eyebrow = "Game",
                                        )
                                    }
                                },
                                onFocusChanged = { focused ->
                                    if (focused) listFocus = ListFocus.Game(game.id) else if (listFocus == ListFocus.Game(game.id)) listFocus = null
                                },
                            ) {
                                GameRow(game, scores.matches[game.id] ?: emptyList(), channelsMatched)
                            }
                        }
                        if (scores.hasLoaded && games.isEmpty()) {
                            item(key = "no-games") {
                                Txt("No games on right now.", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), Type.small, Theme.muted)
                            }
                        }
                        if (streams.isNotEmpty()) {
                            item(key = "channels-heading") {
                                Eyebrow("Sports channels", Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp), Theme.muted)
                            }
                        }
                    }

                    items(streams, key = { "stream-${it.id}" }) { stream ->
                        DisposableEffect(stream.id) {
                            epg.rowAppeared(stream.id)
                            onDispose { epg.rowDisappeared(stream.id) }
                        }
                        TVRow(
                            onClick = { play(stream) },
                            minHeight = Theme.listRowHeight,
                            onLongClick = { openMenu(stream) },
                            onFocusChanged = { focused ->
                                if (focused) listFocus = ListFocus.Stream(stream.id) else if (listFocus == ListFocus.Stream(stream.id)) listFocus = null
                            },
                        ) {
                            LiveChannelRow(stream, epg, isFavorite = !model.showsFavorites && app.favorites.contains(stream.id))
                        }
                    }
                }
                LoadStateView(listState, emptyText = emptyText) {
                    scope.launch {
                        if (model.categories.isEmpty()) model.retryCategories(startWithFavorites) else model.retryStreams()
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (detailGame != null) {
                    GameDetailPane(detailGame, scores.matches[detailGame.id] ?: emptyList(), channelsMatched)
                } else {
                    ChannelDetailPane(
                        stream = detailStream,
                        epg = epg,
                        previewURL = previewURL,
                        previewMuted = app.previewMode != LivePreviewMode.SOUND,
                        onPreviewFailure = { id ->
                            failedPreviews = failedPreviews + id
                            if (previewStreamID == id) previewStreamID = null
                        },
                    )
                }
            }
        }
    }
}

// MARK: Pills and categories

internal fun pillTitle(pill: Pill): String = when (pill) {
    Pill.Favorites -> "Favorites"
    is Pill.Group -> pill.group.title
}

private fun pillIcon(pill: Pill): ImageVector = when (pill) {
    Pill.Favorites -> Icons.Filled.Favorite
    is Pill.Group -> when (pill.group) {
        CategoryGroup.SPORTS -> Icons.Outlined.SportsBasketball
        CategoryGroup.NEWS -> Icons.Outlined.Article
        CategoryGroup.MOVIES -> Icons.Outlined.Movie
        CategoryGroup.SHOWS -> Icons.Outlined.Tv
        CategoryGroup.KIDS -> Icons.Outlined.EmojiEmotions
        CategoryGroup.MUSIC -> Icons.Outlined.MusicNote
        CategoryGroup.WORLD -> Icons.Outlined.Public
        CategoryGroup.OTHER -> Icons.Outlined.GridView
    }
}

/**
 * `down` is the chip row, when there is one. Without it, Down from a pill
 * skips the chips: the focus search compares whole rows by their centers,
 * and the narrow channel list sits more squarely under a pill than the
 * full-width chip row does.
 */
@Composable
private fun PillBar(model: LiveTVModel, down: FocusRequester?, onSelect: (Pill) -> Unit) {
    val pills = listOf<Pill>(Pill.Favorites) + model.groups.map { Pill.Group(it.group) }
    val selected = remember { FocusRequester() }
    LazyRow(
        // Coming up from the list lands on the selected pill.
        Modifier.fillMaxWidth().focusRestorer(selected),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pills, key = { pillTitle(it) }) { pill ->
            val isSelected = model.selection?.pill == pill
            TVPill(
                onClick = { onSelect(pill) },
                modifier = (if (isSelected) Modifier.focusRequester(selected) else Modifier)
                    .focusProperties { if (down != null) this.down = down },
                isSelected = isSelected,
            ) {
                Icon(pillIcon(pill), size = 16.dp)
                Txt(pillTitle(pill), maxLines = 1)
            }
        }
    }
}

/**
 * The selected group's own categories, with "All" first when the group
 * is small enough to merge and a grid button when it is long.
 */
@Composable
private fun CategoryBar(model: LiveTVModel, rowFocus: FocusRequester, openGrid: () -> Unit, onSelect: (String) -> Unit) {
    val subcategories = model.subcategories
    val selectedSub = model.selection?.sub
    val listState = rememberLazyListState()
    val selected = remember { FocusRequester() }

    val leading = buildList {
        if (subcategories.size > 8) add("grid")
        if (model.offersGames) add(LiveTVModel.GAMES_ID)
        if (model.offersAll) add(LiveTVModel.ALL_ID)
    }

    // A pick from the grid may be far along the row.
    LaunchedEffect(model.selection) {
        val sub = model.selection?.sub ?: return@LaunchedEffect
        val index = leading.indexOf(sub).takeIf { it >= 0 }
            ?: subcategories.indexOfFirst { it.id == sub }.takeIf { it >= 0 }?.plus(leading.size)
            ?: return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem(index)
        }
    }

    @Composable
    fun chip(id: String, label: @Composable () -> Unit) {
        TVChip(
            onClick = { onSelect(id) },
            modifier = if (selectedSub == id) Modifier.focusRequester(selected) else Modifier,
            isSelected = selectedSub == id,
        ) {
            label()
        }
    }

    @Composable
    fun chip(title: String, id: String) = chip(id) { Txt(title, maxLines = 1) }

    LazyRow(
        // Coming down from the pills lands on the selected chip.
        Modifier.fillMaxWidth().height(38.dp).focusRequester(rowFocus).focusRestorer(selected),
        state = listState,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (subcategories.size > 8) {
            item(key = "hilltv.grid") {
                TVChip(onClick = openGrid) { Icon(Icons.Outlined.GridView, size = 13.dp) }
            }
        }
        if (model.offersGames) item(key = LiveTVModel.GAMES_ID) { chip("Games", LiveTVModel.GAMES_ID) }
        if (model.offersAll) item(key = LiveTVModel.ALL_ID) { chip("All", LiveTVModel.ALL_ID) }
        items(subcategories, key = { it.id }) { category -> chip(category.id) { CategoryNameLabel(category.displayName) } }
    }
}

// MARK: Channels

/** One channel: number, logo, name, what's on now and how far along it is. */
@Composable
private fun LiveChannelRow(stream: LiveStream, epg: EPGStore, isFavorite: Boolean) {
    val now = epg.now
    val schedule = epg.schedule(stream.id)
    val current = schedule?.current(now)

    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        stream.number?.let {
            Txt(it.toString(), Modifier.width(30.dp), Type.small, secondaryColor(), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End, tabular = true)
        }
        ChannelLogo(stream.iconURL)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelNameLabel(
                    stream.displayName, Modifier.weight(1f, fill = false),
                    style = Type.meta, layout = NameLayout.INLINE, weight = FontWeight.SemiBold, inFocusRow = true,
                )
                if (isFavorite) FavoriteMark()
            }
            Txt(channelSubtitle(schedule, current, now), style = Type.small, color = secondaryColor(), maxLines = 1)
        }
        if (current != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(
                    remainingLabel(EPGSchedule.minutesLeft(current, now)),
                    style = Type.small, color = secondaryColor(), maxLines = 1, tabular = true,
                )
                FocusAwareProgressBar(current.progress(now), Modifier.width(65.dp))
            }
        }
    }
}

private fun channelSubtitle(schedule: EPGSchedule?, current: EPGEntry?, now: Instant): String {
    // Not fetched yet: keep the line's height without saying anything.
    if (schedule == null) return " "
    if (current != null) return current.title
    val next = schedule.next(now) ?: return "No guide data"
    return "Next at ${next.start.shortTime}: ${next.title}"
}

/** Right-hand side: a still or live preview of the focused channel, and the next few hours. */
@Composable
private fun ChannelDetailPane(
    stream: LiveStream?,
    epg: EPGStore,
    previewURL: String?,
    previewMuted: Boolean,
    onPreviewFailure: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (stream == null) {
            Eyebrow("Live TV")
            Txt("Pick a channel to see what's on.", style = Type.meta, color = Theme.muted)
            return@Column
        }
        val now = epg.now
        val schedule = epg.schedule(stream.id)

        ChannelStill(stream, schedule?.current(now), previewURL, previewMuted) { onPreviewFailure(stream.id) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            stream.number?.let { Txt(it.toString(), style = Type.heading, color = Theme.muted, maxLines = 1, tabular = true) }
            ChannelNameLabel(
                stream.displayName, Modifier.weight(1f, fill = false),
                style = Type.subtitle, detailStyle = Type.meta, layout = NameLayout.INLINE, color = Theme.text,
            )
            if (stream.hasArchive) ArchiveMark(size = 13.dp)
        }

        if (schedule == null) {
            Spinner()
        } else {
            val upcoming = schedule.upcoming(now, 4)
            if (upcoming.isEmpty()) {
                Txt("No guide data for this channel.", style = Type.meta, color = Theme.muted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (entry in upcoming) TimelineRow(entry, now)
                }
            }
        }
    }
}

/**
 * Logo card standing in for the picture, with what's on along the bottom.
 * When a preview URL is set, the live picture plays over the logo.
 */
@Composable
private fun ChannelStill(
    stream: LiveStream,
    current: EPGEntry?,
    previewURL: String?,
    previewMuted: Boolean,
    onPreviewFailure: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(max = 220.dp)
            .widescreen()
            .clip(RoundedCornerShape(Theme.radius))
            .background(Theme.surface),
        contentAlignment = Alignment.Center,
    ) {
        ChannelLogo(stream.iconURL, width = 160.dp, height = 100.dp)
        if (previewURL != null) {
            // One player per channel: a new URL tears the old one down.
            androidx.compose.runtime.key(previewURL) {
                LivePreview(previewURL, previewMuted, Modifier.fillMaxSize(), onFailure = onPreviewFailure)
            }
        }
        LiveBadge(Modifier.align(Alignment.TopStart).padding(10.dp))
        if (current != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Theme.background.copy(alpha = 0.78f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Txt(current.title, style = Type.body, color = Theme.text, maxLines = 1, weight = FontWeight.SemiBold)
                current.summary?.takeIf { it.isNotEmpty() }?.let {
                    Txt(it, style = Type.small, color = Theme.muted, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(entry: EPGEntry, now: Instant) {
    val onAir = entry.isOnAir(now)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onAir) Modifier.background(Theme.surface, RoundedCornerShape(Theme.radius)) else Modifier)
            .defaultMinSize(minHeight = 28.dp)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(entry.start.shortTime, Modifier.width(60.dp), Type.small, Theme.muted, maxLines = 1, tabular = true)
        Txt(
            entry.title,
            Modifier.weight(1f),
            Type.meta,
            if (onAir) Theme.text else Theme.muted,
            maxLines = 1,
            weight = if (onAir) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (onAir) {
            ProgressBar(entry.progress(now), Modifier.width(80.dp))
            Txt(remainingLabel(EPGSchedule.minutesLeft(entry, now)), style = Type.small, color = Theme.muted, maxLines = 1, tabular = true)
        }
    }
}

/** A full-screen page with an eyebrow and a title, for pickers. */
@Composable
internal fun CoverPage(eyebrow: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.background)
            .padding(horizontal = 40.dp, vertical = Theme.safeVertical + 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow(eyebrow)
            Txt(title, style = Type.title, color = Theme.text, maxLines = 1)
        }
        content()
    }
}

/** One group's categories in a grid, for groups with far more than fit in the chip row. */
@Composable
private fun CategoryPicker(title: String, categories: List<XtreamCategory>, selectedID: String?, onPick: (String) -> Unit) {
    val first = rememberInitialFocus()
    val focusIndex = categories.indexOfFirst { it.id == selectedID }.coerceAtLeast(0)
    CoverPage("Live TV", title) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(categories, key = { it.id }) { category ->
                TVRow(
                    onClick = { onPick(category.id) },
                    modifier = if (categories.indexOf(category) == focusIndex) Modifier.initialFocus(first) else Modifier,
                    isSelected = category.id == selectedID,
                    minHeight = 32.dp,
                    textStyle = Type.meta,
                ) {
                    CategoryNameLabel(category.displayName)
                }
            }
        }
    }
}
