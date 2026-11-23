@file:OptIn(UnstableApi::class)

package com.beauahill.hilltv.player

import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.beauahill.hilltv.core.PlaybackSettings
import com.beauahill.hilltv.model.AppModel
import com.beauahill.hilltv.ui.Icon
import com.beauahill.hilltv.ui.LocalApp
import com.beauahill.hilltv.ui.TVAction
import com.beauahill.hilltv.ui.Theme
import com.beauahill.hilltv.ui.Txt
import com.beauahill.hilltv.ui.Type
import com.beauahill.hilltv.ui.initialFocus
import com.beauahill.hilltv.ui.rememberInitialFocus
import kotlinx.coroutines.delay

/** Streams from panels: the app's User-Agent, and redirects from http to https or back are followed. */
internal fun mediaSourceFactory(context: Context, userAgent: String): DefaultMediaSourceFactory {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
    return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
}

/**
 * One ExoPlayer for one item. ExoPlayer plays HLS, raw MPEG-TS, mkv and mp4
 * itself, so unlike the Apple TV app there is a single engine.
 *
 * Live channels get a few quiet retries, because a raw MPEG-TS stream simply
 * ends when the panel drops the connection.
 */
internal class PlayerSession(
    context: Context,
    private val item: PlayableItem,
    /** Live buffer and live delay. The defaults change nothing here. */
    private val settings: PlaybackSettings,
    userAgent: String,
    private val onError: (String) -> Unit,
) : Player.Listener {
    val player: ExoPlayer

    private val mediaItem: MediaItem
    private var failures = 0
    private var readySince = 0L
    private var appliedLiveOffset = false
    private var liveTargetMs = C.TIME_UNSET
    private var hasPlayed = false
    private var lastCatchUp = 0L
    private var released = false

    init {
        val builder = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory(context, userAgent))
        if (item.isLive && settings.liveBuffer != PlaybackSettings.LiveBuffer.STANDARD) {
            // The buffer choice only replaces the player's own sizes when it isn't the default.
            val ms = (settings.liveBuffer.seconds * 1000).toInt()
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        ms,
                        ms,
                        minOf(ms, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS),
                        minOf(ms, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS),
                    )
                    .build()
            )
        }
        player = builder.build()
        player.addListener(this)

        mediaItem = MediaItem.Builder()
            .setUri(item.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setSubtitle(item.subtitle).build())
            .build()
        val start = if (item.isLive) null else item.startAt?.takeIf { it > 0 }
        if (start != null) {
            player.setMediaItem(mediaItem, (start * 1000).toLong())
        } else {
            player.setMediaItem(mediaItem)
        }
        player.playWhenReady = true
        player.prepare()
    }

    /** Position and duration in seconds, for saving progress. Null for a live channel. */
    fun progress(): Pair<Double, Double?>? {
        if (released || item.isLive) return null
        val duration = player.duration
        return player.currentPosition / 1000.0 to (if (duration == C.TIME_UNSET || duration <= 0) null else duration / 1000.0)
    }

    fun pause() {
        if (!released) player.pause()
    }

    fun resume() {
        if (released) return
        if (item.isLive) {
            // A live stream has moved on while the app was away.
            player.seekToDefaultPosition()
            player.prepare()
        }
        player.play()
    }

    fun release() {
        if (released) return
        released = true
        player.removeListener(this)
        player.release()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                readySince = System.currentTimeMillis()
                if (hasPlayed) keepUpAfterStall()
                hasPlayed = true
            }
            // A movie that ends is finished. A live channel that ends was dropped.
            Player.STATE_ENDED -> if (item.isLive) retryOrFail("The stream ended.")
            else -> Unit
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        moveCloserToLive(timeline)
    }

    override fun onPlayerError(error: PlaybackException) {
        if (!item.isLive) {
            onError(describe(error))
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
        }
        retryOrFail(describe(error))
    }

    private fun retryOrFail(message: String) {
        // Playing fine for a while before this: start the count again.
        if (readySince > 0 && System.currentTimeMillis() - readySince > 20_000) failures = 0
        readySince = 0
        failures += 1
        if (failures > MAX_RETRIES) {
            onError(message)
        } else {
            // A stream that ended has to be asked for again from its start
            // (for a live stream, that is "now"); preparing alone does nothing.
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            player.prepare()
            player.play()
        }
    }

    /**
     * "Closest to live" only, once per channel, and only for a stream with a
     * live window (HLS). The stream's own distance is known only after it
     * has loaded. Every other setting returns no target and leaves the
     * player exactly as it was.
     */
    private fun moveCloserToLive(timeline: Timeline) {
        if (!item.isLive || appliedLiveOffset || timeline.isEmpty) return
        val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        val configured = window.liveConfiguration?.targetOffsetMs ?: return
        if (!window.isLive() || configured == C.TIME_UNSET) return
        appliedLiveOffset = true
        liveTargetMs = configured
        val target = settings.liveOffsetTarget(configured / 1000.0) ?: return
        liveTargetMs = (target * 1000).toLong()
        try {
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                mediaItem.buildUpon()
                    .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(liveTargetMs).build())
                    .build(),
            )
            // The new distance takes effect on a seek to the live edge.
            player.seekToDefaultPosition()
        } catch (_: RuntimeException) {
            // Leave the stream where the player put it.
        }
    }

    /** "Keep up" and "Closest to live": after a stall, skip ahead instead of staying that much further behind. */
    private fun keepUpAfterStall() {
        if (!item.isLive || !settings.preservesLiveOffset || liveTargetMs == C.TIME_UNSET) return
        val offset = player.currentLiveOffset
        val now = System.currentTimeMillis()
        if (offset == C.TIME_UNSET || offset < liveTargetMs + 3_000 || now - lastCatchUp < 10_000) return
        lastCatchUp = now
        player.seekToDefaultPosition()
    }

    private fun describe(error: PlaybackException): String {
        val cause = error.cause
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return "The server refused the stream (HTTP ${cause.responseCode})."
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't reach the server."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The server doesn't have this stream."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "The stream isn't in a format the player understands."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device can't decode this video or audio format."
            else -> error.message ?: error.errorCodeName
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}

/**
 * Full-screen player. Shows a readable error if playback fails, instead of a
 * silent black screen. Back hides the controls if they are showing,
 * otherwise closes the player.
 */
@Composable
fun PlayerScreen(item: PlayableItem, onClose: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    val error = errorMessage
    if (error != null) {
        BackHandler(onBack = onClose)
        PlaybackErrorView(item, error, onClose)
        return
    }

    val session = remember(item) {
        PlayerSession(context.applicationContext, item, app.playback, AppModel.USER_AGENT) { errorMessage = it }
    }

    fun saveProgress() {
        session.progress()?.let { (position, duration) -> app.recordProgress(item, position, duration) }
    }

    DisposableEffect(session) {
        onDispose {
            // Save the final position before letting the player go.
            saveProgress()
            session.release()
        }
    }

    if (!item.isLive && item.history != null) {
        LaunchedEffect(session) {
            while (true) {
                delay(10_000)
                saveProgress()
            }
        }
    }

    // Leaving for the home screen stops playback and frees the connection's bandwidth.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        saveProgress()
        session.pause()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { session.resume() }

    BackHandler {
        val view = playerView
        if (view != null && controlsVisible) view.hideController() else onClose()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    // Before the player is attached, or the controls flash up at the start.
                    controllerAutoShow = false
                    player = session.player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowSubtitleButton(true)
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    if (item.isLive) {
                        setShowRewindButton(false)
                        setShowFastForwardButton(false)
                    }
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility -> controlsVisible = visibility == android.view.View.VISIBLE }
                    )
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    // The remote drives the player's own controls, so the view needs real focus.
                    post { requestFocus() }
                    playerView = this
                }
            },
            onRelease = { view ->
                view.player = null
                playerView = null
            },
        )

        if (controlsVisible) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .padding(horizontal = 40.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Txt(item.title, style = Type.subtitle, color = Theme.text, maxLines = 1)
                item.subtitle?.let { Txt(it, style = Type.meta, color = Theme.muted, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun PlaybackErrorView(item: PlayableItem, message: String, close: () -> Unit) {
    val closeFocus = rememberInitialFocus()
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 600.dp).padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Warning, size = 45.dp, tint = Theme.text)
            Txt("Couldn't play ${item.title}", style = Type.subtitle, color = Theme.text, textAlign = TextAlign.Center)
            Txt(message, style = Type.meta, color = Theme.muted, textAlign = TextAlign.Center)
            if (item.isLive) {
                Txt(
                    "If other channels play, this one may be down. If none do, try the other live stream format in Settings.",
                    style = Type.small, color = Theme.muted, textAlign = TextAlign.Center,
                )
            }
            // The file name only: the rest of the address holds the login.
            Txt(
                item.url.substringBefore('?').substringAfterLast('/'),
                style = Type.small.copy(fontFamily = FontFamily.Monospace),
                color = Theme.muted.copy(alpha = 0.6f),
                maxLines = 1,
            )
            TVAction("Close", close, Modifier.initialFocus(closeFocus), prominent = true)
        }
    }
}
