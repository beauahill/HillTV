package com.beauahill.hilltv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.text.BasicText
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.model.AppModel
import com.beauahill.hilltv.model.EPGStore
import com.beauahill.hilltv.model.LiveTVModel
import com.beauahill.hilltv.model.ScoreboardStore
import com.beauahill.hilltv.player.PlayerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val LocalApp = staticCompositionLocalOf<AppModel> { error("AppModel is not provided") }

@Composable
fun HillTVRoot(app: AppModel) {
    LaunchedEffect(app) { app.restore() }
    val covers = remember { CoverHost() }
    val menus = remember { HoldMenuHost() }
    CompositionLocalProvider(
        LocalApp provides app,
        LocalCoverHost provides covers,
        LocalHoldMenu provides menus,
        LocalContentColor provides Theme.text,
    ) {
        Box(Modifier.fillMaxSize().background(Theme.background)) {
            val client = app.client
            when {
                app.phase == AppModel.Phase.LAUNCHING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                app.phase == AppModel.Phase.SIGNED_IN && client != null -> {
                    // A new client (sign out, then in) gets fresh section state.
                    key(client) {
                        val item = app.nowPlaying
                        Covered(
                            covered = item != null,
                            cover = { if (item != null) key(item.id) { PlayerScreen(item, onClose = app::closePlayer) } },
                            coverColor = Color.Black,
                        ) {
                            CoverLayer(covers.entries, 0) { MainShell(client) }
                        }
                        // Declared after the shell, so the menu is the newest cover and sits on top.
                        HoldMenuCover(menus)
                    }
                }
                else -> LoginScreen()
            }
            Notice(app)
        }
    }
}

/**
 * The covers as a stack: each one sits over the one before, and every page
 * underneath stays composed. That matters for the multiview, whose list of
 * channels to add opens over it while its channels keep playing.
 */
@Composable
private fun CoverLayer(entries: List<CoverHost.Entry>, index: Int, base: @Composable () -> Unit) {
    val next = entries.getOrNull(index)
    Covered(
        covered = next != null,
        coverKey = next,
        cover = {
            if (next != null) {
                key(next) {
                    CoverLayer(entries, index + 1) {
                        BackHandler { next.onDismiss() }
                        next.content()
                    }
                }
            }
        },
        base = base,
    )
}

/** Full-screen pages opened from inside a section: Search, pickers, menus. They stack, newest on top. */
class CoverHost {
    class Entry(val content: @Composable () -> Unit, val onDismiss: () -> Unit)

    val entries = mutableStateListOf<Entry>()
}

val LocalCoverHost = staticCompositionLocalOf<CoverHost> { error("CoverHost is not provided") }

/**
 * Shows `content` over the whole screen, rail included, while `visible`.
 * Back calls `onDismiss`. The Android stand-in for SwiftUI's fullScreenCover.
 */
@Composable
fun FullScreenCover(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (!visible) return
    val host = LocalCoverHost.current
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host) {
        val entry = CoverHost.Entry({ currentContent() }, { currentDismiss() })
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

/** A line of feedback at the bottom of the screen that goes away by itself. */
@Composable
private fun Notice(app: AppModel) {
    val message = app.notice ?: return
    LaunchedEffect(message) {
        delay(2200)
        app.clearNotice()
    }
    Box(Modifier.fillMaxSize().zIndex(5f).padding(bottom = 36.dp), contentAlignment = Alignment.BottomCenter) {
        Txt(
            message,
            Modifier.background(Theme.controlRaised, RoundedCornerShape(Theme.radius)).padding(horizontal = 18.dp, vertical = 10.dp),
            Type.meta,
            Theme.text,
            maxLines = 1,
            weight = FontWeight.SemiBold,
        )
    }
}

/**
 * A page with another page over it: the player over the shell, Search over a
 * section, a detail page over a grid.
 *
 * The page underneath stays alive, so it keeps its scroll position and comes
 * back as it was, but while it is covered focus can't get into it. When the
 * cover closes, focus goes back to whatever had it before.
 *
 * Staying alive also means it keeps running. Anything underneath that holds
 * a connection or polls has to stop itself while it is covered.
 */
@Composable
fun Covered(
    covered: Boolean,
    cover: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    coverColor: Color = Theme.background,
    /** Changes when one cover is swapped for another without the page underneath showing in between. */
    coverKey: Any? = null,
    base: @Composable () -> Unit,
) {
    val baseFocus = remember { FocusRequester() }
    val coverFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val isCovered by rememberUpdatedState(covered)
    val wasCovered = remember { booleanArrayOf(false) }
    // The control that had focus when the cover went up.
    val returnTo = remember { arrayOfNulls<FocusRequester>(1) }
    val baseHasFocus = remember { booleanArrayOf(false) }

    LaunchedEffect(covered, coverKey) {
        if (covered) {
            // A menu that opens another page swaps one cover for the next. The
            // control to go back to is still the one from before the first.
            if (!wasCovered[0]) returnTo[0] = FocusMemory.last
            try {
                // Take focus off the page underneath even before the cover has anything to focus.
                coverFocus.requestFocus()
            } catch (_: IllegalStateException) {
            }
        } else if (wasCovered[0]) {
            // False when that control is gone (its list reloaded, say).
            val restored = try {
                returnTo[0]?.requestFocus() == true
            } catch (_: IllegalStateException) {
                false
            }
            // If the page underneath already took focus back itself, leave it there.
            if (!restored && !baseHasFocus[0]) {
                try {
                    baseFocus.requestFocus()
                } catch (_: IllegalStateException) {
                }
            }
            returnTo[0] = null
        }
        wasCovered[0] = covered
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .focusProperties { onEnter = { if (isCovered) cancelFocusChange() } }
                .focusGroup()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { baseHasFocus[0] = it.hasFocus }
                    .focusRequester(baseFocus)
                    .focusGroup()
            ) {
                base()
            }
        }
        if (covered) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .background(coverColor)
                    .focusRequester(coverFocus)
                    // The cover itself only holds focus while it has nothing to show.
                    // As soon as it does, pass focus in, so something is always lit.
                    .onFocusChanged { if (it.isFocused) scope.launch { focusManager.moveFocus(FocusDirection.Enter) } }
                    .focusable()
            ) {
                cover()
            }
        }
    }
}

enum class AppSection(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    SEARCH("Search", Icons.Outlined.Search),
    LIVE("Live TV", Icons.Outlined.Tv),
    GUIDE("Guide", Icons.Outlined.CalendarMonth),
    FAVORITES("Favorites", Icons.Outlined.FavoriteBorder),
    MOVIES("Movies", Icons.Outlined.Movie),
    SERIES("Series", Icons.Outlined.GridView),
    SETTINGS("Settings", Icons.Outlined.Tune),
}

/** Section models live here so switching sections keeps each one's categories and caches. */
class ShellState(val client: XtreamClient, app: AppModel) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val epg = EPGStore(client, scope)
    val scores = ScoreboardStore(AppModel.USER_AGENT)
    val live = LiveTVModel(client, app.categoryVisibility, app.channelOrder)
    val home = HomeModel(client, app.categoryVisibility)
    val search = SearchModel(client, app.categoryVisibility)
    val guide = GuideModel(client, app.categoryVisibility, app.channelOrder, scope)
    val movies = MoviesModel(client, app.categoryVisibility)
    val series = SeriesModel(client, app.categoryVisibility)

    fun close() = scope.cancel()
}

/**
 * Icon rail on the left, the selected section on the right.
 *
 * Search is the exception: it opens as a full-screen page over the shell,
 * as it does on the Apple TV, so the keyboard has the whole screen.
 *
 * Back at a section's root goes to the rail. Back on the rail leaves the
 * app, which is what Google TV expects of the top level.
 */
@Composable
fun MainShell(client: XtreamClient) {
    val app = LocalApp.current
    val state = remember(client) { ShellState(client, app) }
    DisposableEffect(state) { onDispose { state.close() } }

    var section by remember { mutableStateOf(AppSection.HOME) }
    var showingSearch by remember { mutableStateOf(false) }
    var railFocused by remember { mutableStateOf(false) }
    val railItems = remember { AppSection.entries.associateWith { FocusRequester() } }
    val contentFocus = remember { FocusRequester() }

    // Keeps "on now" progress moving while the shell is on screen.
    LaunchedEffect(state) { state.epg.runClock() }

    // Start in the content, and come back to it when Search closes. The
    // section is taken down behind Search, so the control that opened it is
    // gone, and focus falls to the rail the moment that happens. It can take
    // a few frames before anything in the content can take focus; only then
    // fall back to the rail.
    val covers = LocalCoverHost.current
    val wasSearching = remember { booleanArrayOf(false) }
    LaunchedEffect(state, showingSearch) {
        if (showingSearch) {
            wasSearching[0] = true
            return@LaunchedEffect
        }
        if (wasSearching[0]) {
            wasSearching[0] = false
            // Let the Search page finish closing and hand focus back first.
            snapshotFlow { covers.entries.isEmpty() }.first { it }
            repeat(2) { withFrameNanos { } }
        }
        try {
            repeat(20) {
                if (contentFocus.requestFocus(FocusDirection.Enter)) return@LaunchedEffect
                withFrameNanos { }
            }
            railItems.getValue(section).requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    // When a cover closes, focus goes back to the control that had it. If
    // that control is gone (a favorite just removed from its own menu, say),
    // focus falls to the rail. Put it back in the content instead, when the
    // content has something to take it.
    val covered = covers.entries.isNotEmpty() || app.nowPlaying != null
    LaunchedEffect(covered) {
        if (covered || showingSearch) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        if (railFocused) {
            try {
                contentFocus.requestFocus(FocusDirection.Enter)
            } catch (_: IllegalStateException) {
            }
        }
    }

    // At a section's root, Back goes to the rail. Anything opened inside a
    // section is declared later, so its own handler runs first and closes it.
    BackHandler(enabled = !railFocused) {
        try {
            railItems.getValue(section).requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    FullScreenCover(visible = showingSearch, onDismiss = { showingSearch = false }) {
        SearchScreen(state.search)
    }

    // The multiview is opened from hold-Select menus all over the app, so its
    // switch lives on `AppModel` and the cover lives here. The section behind
    // stays up, keeping its place for adding another channel; Live TV stops
    // its preview and scores while the multiview shows.
    FullScreenCover(visible = app.multiview.isShowing, onDismiss = { app.multiview.isShowing = false }) {
        MultiviewScreen(client, state.epg)
    }

    run {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = Sidebar.width + 12.dp,
                        end = Theme.safeHorizontal,
                        top = Theme.safeVertical,
                        bottom = Theme.safeVertical,
                    )
                    .focusRequester(contentFocus)
                    .focusRestorer()
                    .focusGroup()
            ) {
                // The section is taken down while Search covers it, so nothing behind
                // the cover keeps working: no live preview holding a connection, no
                // scores polling. Its model keeps the state for when it comes back.
                if (!showingSearch) {
                    when (section) {
                        AppSection.HOME -> HomeScreen(state.home, state.epg)
                        AppSection.SEARCH -> Unit
                        AppSection.LIVE -> LiveTVScreen(state.live, state.epg, state.scores)
                        AppSection.GUIDE -> GuideScreen(state.guide)
                        AppSection.FAVORITES -> FavoritesScreen(client)
                        AppSection.MOVIES -> MoviesScreen(state.movies)
                        AppSection.SERIES -> SeriesScreen(state.series)
                        AppSection.SETTINGS -> SettingsScreen()
                    }
                }
            }

            Sidebar(
                selection = section,
                expanded = railFocused,
                items = railItems,
                onFocusChanged = { railFocused = it },
                // Picking Search opens the search page and leaves the current
                // section selected, so Back returns to where the user was.
                onSelect = { item -> if (item == AppSection.SEARCH) showingSearch = true else section = item },
            )
        }
    }
}

/**
 * Icon rail. It widens to show labels while one of its items has focus, and
 * draws over the content without moving it.
 *
 * Selection changes on click, not focus, so passing through the rail on the
 * way back from the content doesn't switch sections.
 */
object Sidebar {
    /** Left edge of the screen to the right edge of the collapsed rail. */
    val width = 24.dp + Theme.railWidth
    internal val inset = 6.dp
    internal val itemSide = Theme.railWidth - inset * 2

    @Composable
    operator fun invoke(
        selection: AppSection,
        expanded: Boolean,
        items: Map<AppSection, FocusRequester>,
        onFocusChanged: (Boolean) -> Unit,
        onSelect: (AppSection) -> Unit,
    ) {
        val panel by animateDpAsState(
            if (expanded) 24.dp + Theme.railExpandedWidth else width,
            Theme.focusAnimation(),
            label = "rail",
        )
        Column(
            Modifier
                .zIndex(1f)
                .fillMaxHeight()
                .width(panel)
                .drawBehind {
                    drawRect(Theme.sidebar)
                    drawLine(Theme.line, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
                }
                .padding(start = 24.dp + inset, end = inset, top = Theme.safeVertical + 10.dp, bottom = Theme.safeVertical + 10.dp)
                .onFocusChanged { onFocusChanged(it.hasFocus) }
                // Coming in from the content lands on the selected section.
                .focusRestorer(items.getValue(selection))
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            RailMark(expanded)
            Gap(height = 17.dp)
            for (item in AppSection.entries) {
                if (item == AppSection.SETTINGS) Spacer(Modifier.weight(1f))
                RailButton(item, item == selection, expanded, items.getValue(item)) { onSelect(item) }
            }
        }
    }
}

/** Focus: blue fill with dark icon and label. Selected: blue tint and icon. */
@Composable
private fun RailButton(item: AppSection, isSelected: Boolean, expanded: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    FocusBox(onClick, Modifier.fillMaxWidth().focusRequester(requester)) { focused, pressed ->
        val fill = when {
            focused -> Theme.accent
            isSelected -> Theme.accent.copy(alpha = 0.18f)
            else -> Color.Transparent
        }
        val iconColor = when {
            focused -> Theme.onAccent
            isSelected -> Theme.accent
            else -> Theme.muted
        }
        val labelAlpha by animateFloatAsState(if (expanded) 1f else 0f, Theme.focusAnimation(), label = "label")
        Row(
            Modifier
                .fillMaxWidth()
                .height(Theme.rowHeight)
                .alpha(if (pressed) 0.85f else 1f)
                .background(fill, RoundedCornerShape(Theme.radius)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(Sidebar.itemSide, Theme.rowHeight), contentAlignment = Alignment.Center) {
                Icon(item.icon, size = 17.dp, tint = iconColor)
            }
            if (expanded) {
                Txt(
                    item.title,
                    Modifier.padding(start = 2.dp).alpha(labelAlpha),
                    Type.meta,
                    if (focused) Theme.onAccent else Theme.text,
                    maxLines = 1,
                    weight = FontWeight.Medium,
                )
            }
        }
    }
}

/** The "H" badge at the top of the rail, with the wordmark beside it when expanded. */
@Composable
private fun RailMark(expanded: Boolean) {
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(Sidebar.itemSide), contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp).background(Theme.accent, RoundedCornerShape(Theme.radius)), contentAlignment = Alignment.Center) {
                Txt("H", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = Type.condensedFamily), color = Theme.onAccent)
            }
        }
        if (expanded) Wordmark(18.sp, Modifier.padding(start = 2.dp))
    }
}

/** "Hill" in white, "TV" in the accent color. Text only. */
@Composable
fun Wordmark(size: TextUnit = 22.sp, modifier: Modifier = Modifier) {
    BasicText(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Theme.text)) { append("Hill") }
            withStyle(SpanStyle(color = Theme.accent)) { append("TV") }
        },
        modifier,
        TextStyle(fontSize = size, fontWeight = FontWeight.Black),
        maxLines = 1,
        softWrap = false,
    )
}
