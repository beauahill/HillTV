package com.beauahill.hilltv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.beauahill.hilltv.core.LiveFormat
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.Multiview
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.core.displayName
import com.beauahill.hilltv.model.EPGStore
import com.beauahill.hilltv.model.LivePreviewMode
import com.beauahill.hilltv.player.LivePreview
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val tileSpacing = 6.dp

/**
 * Several live channels on screen at once, in an even grid: two side by
 * side, three or four as two by two.
 *
 * Sound follows focus. Select shows the focused channel full screen and
 * Select or Back brings the grid back; the other channels keep playing
 * underneath, so nothing reconnects. Hold Select opens a menu with Remove.
 *
 * Every tile is its own connection to the provider, and its own ExoPlayer
 * with its own video decoder. One channel always plays. A channel that would
 * go past the account's reported connections (`AppModel.multiviewLimit`)
 * asks first, once, and the answer is kept. Each tile asks for a picture no
 * bigger than it is shown (`Multiview.pictureCap`). Tiles ask for HLS;
 * Settings > Multiview has a trial that asks for raw MPEG-TS instead, and a
 * provider that offers no HLS gets MPEG-TS without being asked.
 *
 * The grid stays inside the safe area, so no tile or focus ring is lost to
 * a TV's overscan. Only a tile shown full screen runs to the edges.
 */
@Composable
fun MultiviewScreen(client: XtreamClient, epg: EPGStore) {
    val app = LocalApp.current
    val menus = LocalHoldMenu.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inForeground = lifecycle.isAtLeast(Lifecycle.State.RESUMED)

    val tiles = app.multiview.tiles.streams

    /** The channel with sound: the last tile that held focus. */
    var soundID by remember { mutableStateOf<Int?>(null) }
    /** The channel shown full screen, if one is. */
    var fullScreenID by remember { mutableStateOf<Int?>(null) }
    /** True while the list of channels to add is open. */
    var picking by remember { mutableStateOf(false) }
    /** True while the plus tile is asking about the connection count. */
    var askingForMore by remember { mutableStateOf(false) }
    /** Why a channel isn't playing, by stream ID. */
    val errors = remember { mutableStateMapOf<Int, String>() }
    /** Bumped to give a failed channel a new player. */
    val attempts = remember { mutableStateMapOf<Int, Int>() }
    /** False for a moment after opening, while Live TV's preview lets go of its connection. */
    var ready by remember { mutableStateOf(false) }
    var showsHint by remember { mutableStateOf(true) }
    /** A channel just added, to be given focus once its tile exists. */
    var focusNext by remember { mutableStateOf<Int?>(null) }
    val tileFocus = remember { HashMap<Int, FocusRequester>() }
    val firstFocus = rememberInitialFocus()

    // The plus tile shows until the screen is full. Whether the account has
    // room for another channel is asked when it is pressed.
    val canAdd = tiles.size < Multiview.MOST_TILES
    val soundStreamID = soundID ?: tiles.firstOrNull()?.id
    val isPlaying = ready && inForeground
    val format = if (app.multiviewPlaysTS || !app.previewSupported) LiveFormat.MPEGTS else LiveFormat.HLS
    val fullScreenIndex = tiles.indexOfFirst { it.id == fullScreenID }
    // The question is up when the plus tile asked, or when a channel added
    // from a hold-Select menu is waiting on it.
    val asking = askingForMore || app.multiview.waiting != null

    LaunchedEffect(tiles) {
        val ids = tiles.map { it.id }.toSet()
        if (fullScreenID != null && fullScreenID !in ids) fullScreenID = null
        if (soundID != null && soundID !in ids) soundID = null
        tileFocus.keys.retainAll(ids)
    }

    LaunchedEffect(Unit) {
        // The preview in Live TV stops when this opens, and a panel can take
        // a moment to free that connection.
        if (app.previewMode != LivePreviewMode.OFF) delay(700)
        ready = true
        delay(7_000)
        showsHint = false
    }

    val notice = app.multiview.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6_000)
            app.multiview.clearNotice()
        }
    }

    // The new tile has to exist, and the list that added it has to have
    // closed and handed focus back, before the tile can take it.
    LaunchedEffect(focusNext, tiles, picking, asking) {
        val id = focusNext ?: return@LaunchedEffect
        if (picking || asking || tiles.none { it.id == id }) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        try {
            tileFocus[id]?.requestFocus()
        } catch (_: IllegalStateException) {
        }
        focusNext = null
    }

    // Back leaves full screen first. With nothing full screen, the cover's own handler closes the multiview.
    BackHandler(enabled = fullScreenID != null) { fullScreenID = null }

    fun retry(streamID: Int) {
        errors.remove(streamID)
        attempts[streamID] = (attempts[streamID] ?: 0) + 1
    }

    fun remove(streamID: Int) {
        errors.remove(streamID)
        attempts.remove(streamID)
        app.multiview.remove(streamID)
    }

    /** The plus tile. One more channel than the account reports connections for is a question first. */
    fun addPressed() {
        if (tiles.size < app.multiviewLimit) picking = true else askingForMore = true
    }

    /** "Try anyway": kept, so the question isn't asked again. Settings can undo it. */
    fun tryAnyway() {
        app.multiviewIgnoresLimit = true
        val waiting = app.multiview.waiting
        if (waiting != null) {
            focusNext = waiting.id
            app.multiview.addWaiting(app.multiviewLimit)
        } else {
            picking = true
        }
        askingForMore = false
    }

    fun stopAsking() {
        askingForMore = false
        app.multiview.dropWaiting()
    }

    fun openMenu(stream: LiveStream) {
        val isFullScreen = fullScreenID == stream.id
        menus.show(
            stream.displayName.title,
            listOf(
                MenuAction(if (isFullScreen) "Back to the grid" else "Full screen") {
                    fullScreenID = if (isFullScreen) null else stream.id
                },
                MenuAction("Remove") { remove(stream.id) },
                MenuAction("Close Multiview") { app.multiview.isShowing = false },
            ),
            eyebrow = "Multiview",
        )
    }

    // Favorites that aren't on screen yet. Any other channel is added from
    // its hold-Select menu elsewhere in the app.
    FullScreenCover(visible = picking, onDismiss = { picking = false }) {
        MultiviewPicker(
            candidates = app.favorites.channels.filter { !app.multiview.tiles.contains(it.id) },
            hasFavorites = app.favorites.channels.isNotEmpty(),
            onClose = { picking = false },
        ) { stream ->
            picking = false
            focusNext = stream.id
            app.multiview.add(stream, app.multiviewLimit)
        }
    }

    FullScreenCover(visible = asking, onDismiss = ::stopAsking) {
        ConnectionAsk(app.reportedConnections, onTry = ::tryAnyway, onCancel = ::stopAsking)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // One layout, with each tile keyed by its channel, so going full
        // screen only changes where a tile is placed. Its player is never
        // taken apart and put back.
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                tiles.forEachIndexed { index, stream ->
                    key(stream.id) {
                        val isFullScreen = fullScreenID == stream.id
                        val hidden = fullScreenID != null && !isFullScreen
                        val requester = tileFocus.getOrPut(stream.id) { FocusRequester() }
                        DisposableEffect(stream.id) {
                            epg.rowAppeared(stream.id)
                            onDispose { epg.rowDisappeared(stream.id) }
                        }
                        MultiviewTile(
                            stream = stream,
                            url = if (isPlaying) client.liveURL(stream.id, format) else null,
                            attempt = attempts[stream.id] ?: 0,
                            muted = stream.id != soundStreamID,
                            cap = Multiview.pictureCap(tiles.size, isFullScreen),
                            nowTitle = epg.schedule(stream.id)?.current(epg.now)?.title,
                            error = errors[stream.id],
                            isFullScreen = isFullScreen,
                            // Covered tiles keep playing, but can't take focus.
                            enabled = !hidden,
                            modifier = Modifier.focusRequester(requester).then(if (index == 0) Modifier.initialFocus(firstFocus) else Modifier),
                            onClick = {
                                if (errors[stream.id] != null) retry(stream.id) else fullScreenID = if (isFullScreen) null else stream.id
                            },
                            onLongClick = { openMenu(stream) },
                            onFocused = { soundID = stream.id },
                            onError = { reason -> errors[stream.id] = reason },
                        )
                    }
                }
                if (canAdd) {
                    key("add") {
                        AddTile(
                            enabled = fullScreenID == null,
                            modifier = if (tiles.isEmpty()) Modifier.initialFocus(firstFocus) else Modifier,
                            onClick = ::addPressed,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val insetX = Theme.safeHorizontal.roundToPx()
            val insetY = Theme.safeVertical.roundToPx()
            val frames = Multiview.frames(
                cells = measurables.size,
                width = (constraints.maxWidth - insetX * 2).toDouble(),
                height = (constraints.maxHeight - insetY * 2).toDouble(),
                spacing = tileSpacing.toPx().toDouble(),
            )
            val placeables = measurables.mapIndexed { index, measurable ->
                when {
                    index == fullScreenIndex -> measurable.measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight))
                    index < frames.size ->
                        measurable.measure(Constraints.fixed(frames[index].width.roundToInt(), frames[index].height.roundToInt()))
                    else -> null
                }
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    when {
                        placeable == null -> Unit
                        index == fullScreenIndex -> placeable.place(0, 0, zIndex = 1f)
                        else -> placeable.place(insetX + frames[index].x.roundToInt(), insetY + frames[index].y.roundToInt())
                    }
                }
            }
        }

        // Messages over the grid.
        if (notice != null) {
            Banner(notice, Modifier.align(Alignment.TopCenter).padding(top = Theme.safeVertical + 4.dp))
        }
        if (showsHint && fullScreenID == null && tiles.isNotEmpty()) {
            Banner(
                "Select: full screen. Hold Select: more. Back: close.",
                Modifier.align(Alignment.BottomCenter).padding(bottom = Theme.safeVertical + 4.dp),
            )
        }
    }
}

/** One channel: its logo until the picture starts, then the picture. */
@Composable
private fun MultiviewTile(
    stream: LiveStream,
    /** Null while nothing should play: just opened, or the app is in the background. */
    url: String?,
    attempt: Int,
    muted: Boolean,
    cap: Multiview.PictureCap?,
    nowTitle: String?,
    error: String?,
    isFullScreen: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    onError: (String) -> Unit,
) {
    val shape = RoundedCornerShape(if (isFullScreen) 0.dp else 6.dp)
    FocusBox(
        onClick,
        modifier,
        enabled = enabled,
        onLongClick = onLongClick,
        onFocusChanged = { if (it) onFocused() },
    ) { focused, _ ->
        Box(Modifier.fillMaxSize().clip(shape).background(Theme.surface), contentAlignment = Alignment.Center) {
            ChannelLogo(stream.iconURL, width = 120.dp, height = 75.dp)
            if (url != null && error == null) {
                // One player per channel and per try.
                key(url, attempt) {
                    LivePreview(url, muted, Modifier.fillMaxSize(), maxVideoSize = cap, keepsScreenOn = true, onError = onError)
                }
            }
            if (!muted && !isFullScreen) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).background(Theme.background.copy(alpha = 0.7f), CircleShape).padding(6.dp)
                ) {
                    Icon(Icons.Filled.VolumeUp, size = 12.dp, tint = Theme.text)
                }
            }
            // The name shows on the focused tile only, so it doesn't sit on
            // a score or a ticker in the others.
            if (error != null || (focused && !isFullScreen)) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Theme.background.copy(alpha = 0.75f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Txt(stream.displayName.title, style = Type.small, color = Theme.text, maxLines = 1, weight = FontWeight.SemiBold)
                    if (error != null) {
                        Txt("$error Select to try again.", style = Type.small, color = Theme.muted, maxLines = 3)
                    } else if (nowTitle != null) {
                        Txt(nowTitle, style = Type.small, color = Theme.muted, maxLines = 1)
                    }
                }
            }
            if (focused && !isFullScreen) {
                Box(Modifier.fillMaxSize().border(Theme.focusRing, Theme.accent, shape))
            }
        }
    }
}

@Composable
private fun AddTile(enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    FocusBox(onClick, modifier, enabled = enabled) { focused, _ ->
        Column(
            Modifier
                .fillMaxSize()
                .background(if (focused) Theme.controlRaised else Theme.surface, shape)
                .then(if (focused) Modifier.border(Theme.focusRing, Theme.accent, shape) else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val color = if (focused) Theme.text else Theme.muted
            Icon(Icons.Filled.Add, size = 32.dp, tint = color)
            Txt("Add a channel", style = Type.meta, color = color, maxLines = 1)
        }
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier = Modifier) {
    Txt(
        text,
        modifier.background(Theme.background.copy(alpha = 0.8f), CircleShape).padding(horizontal = 14.dp, vertical = 7.dp),
        Type.small, Theme.text, maxLines = 2, weight = FontWeight.SemiBold,
    )
}

/** The list of channels the plus tile offers: favorites not on screen yet. */
@Composable
private fun MultiviewPicker(candidates: List<LiveStream>, hasFavorites: Boolean, onClose: () -> Unit, onPick: (LiveStream) -> Unit) {
    val first = rememberInitialFocus()
    CoverPage("Add to Multiview", "Favorites") {
        if (candidates.isEmpty()) {
            Txt(
                if (hasFavorites) "Every favorite is already on screen." else "No favorites yet.",
                Modifier.padding(start = 18.dp), Type.meta, Theme.muted,
            )
            TVAction("Close", onClose, Modifier.padding(start = 14.dp).initialFocus(first), prominent = true)
        } else {
            LazyColumn(
                Modifier.weight(1f, fill = false).widthIn(max = 600.dp),
                contentPadding = PaddingValues(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(candidates, key = { _, stream -> stream.id }) { index, stream ->
                    TVRow(
                        onClick = { onPick(stream) },
                        modifier = if (index == 0) Modifier.initialFocus(first) else Modifier,
                        minHeight = Theme.listRowHeight,
                    ) {
                        ChannelRow(stream, isFavorite = false)
                    }
                }
            }
        }
        Txt(
            "For any other channel, hold Select on it in Live TV, the Guide or Favorites and pick Add to Multiview.",
            Modifier.padding(start = 18.dp), Type.small, Theme.muted,
        )
    }
}

/** Asked before a channel goes past the connections the account reports. */
@Composable
private fun ConnectionAsk(reported: Int?, onTry: () -> Unit, onCancel: () -> Unit) {
    val first = rememberInitialFocus()
    val count = reported ?: 1
    val connections = if (count == 1) "1 connection" else "$count connections"
    Column(
        Modifier.fillMaxSize().background(Theme.background).padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.GridView, size = 42.dp, tint = Theme.muted)
        Txt("Add another channel?", style = Type.title, color = Theme.text)
        Txt(
            "Your provider reports $connections for this account, and every channel on screen is a connection. " +
                "Providers don't always enforce what they report, so you can try. If yours does, a channel will " +
                "fail to start, or one that was playing will stop.",
            Modifier.widthIn(max = 500.dp), Type.meta, Theme.muted, textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TVAction("Try anyway", onTry, Modifier.initialFocus(first), prominent = true)
            TVAction("Not now", onCancel)
        }
    }
}
