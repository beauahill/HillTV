@file:OptIn(UnstableApi::class)

package com.beauahill.hilltv.player

import android.graphics.Color
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.beauahill.hilltv.R
import com.beauahill.hilltv.core.Multiview
import com.beauahill.hilltv.model.AppModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A live picture with no controls: the Live TV detail pane's preview, and
 * each tile of the multiview.
 *
 * One call plays one URL: a new channel gets a new player and the old one
 * is torn down. The preview always asks for HLS; the multiview passes
 * whichever format it is set to.
 *
 * The defaults are the preview's. The multiview passes its own.
 */
@Composable
fun LivePreview(
    url: String,
    muted: Boolean,
    modifier: Modifier = Modifier,
    /**
     * The largest picture to ask for, or null for no limit. It only matters
     * when the stream comes in more than one quality. The preview's pane is
     * under half the screen, so it never pulls a 4K rendition.
     */
    maxVideoSize: Multiview.PictureCap? = Multiview.PictureCap(1280, 720),
    /** A preview left on screen shouldn't keep the TV awake. Channels being watched should. */
    keepsScreenOn: Boolean = false,
    /** The stream couldn't be played. The caller falls back to a still. */
    onFailure: () -> Unit = {},
    /**
     * The same, with the reason, for callers that show it. Also called when
     * a stream that was playing stops for good, which `onFailure` is not:
     * the multiview needs to say when the provider cuts a channel off, and
     * the preview is left as it was.
     */
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failure by rememberUpdatedState(onFailure)
    val report by rememberUpdatedState(onError)

    val player = remember(url) {
        ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(mediaSourceFactory(context.applicationContext, AppModel.USER_AGENT))
            .build()
            .apply {
                volume = if (muted) 0f else 1f
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
    }

    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }

    // The multiview changes this as channels are added and as a tile goes full screen.
    LaunchedEffect(player, maxVideoSize) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (maxVideoSize == null) builder.clearVideoSizeConstraints() else builder.setMaxVideoSize(maxVideoSize.width, maxVideoSize.height)
        player.trackSelectionParameters = builder.build()
    }

    val view = remember { arrayOfNulls<PlayerView>(1) }
    DisposableEffect(player) {
        var reportedStop = false
        var hasPlayed = false
        var stallCheck: Job? = null

        fun stopped(reason: String) {
            if (reportedStop) return
            reportedStop = true
            report(reason)
        }

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Fell out of the back of the live window: go to the live edge and carry on.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && !reportedStop) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                failure()
                stopped(
                    if (isDecoderError(error)) {
                        "This device couldn't run another video decoder. It may not be able to play this many channels at once."
                    } else {
                        describePlaybackError(error)
                    }
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                stallCheck?.cancel()
                stallCheck = null
                when (playbackState) {
                    Player.STATE_READY -> hasPlayed = true
                    // A raw stream simply ends when the provider closes it.
                    Player.STATE_ENDED -> stopped("The stream stopped. The provider may have closed it.")
                    // A stall is often a few seconds of loading. Only one that
                    // hasn't recovered after a while counts as stopped.
                    Player.STATE_BUFFERING -> if (hasPlayed) {
                        stallCheck = scope.launch {
                            delay(15_000)
                            if (player.playbackState == Player.STATE_BUFFERING) {
                                stopped("The stream stopped loading. The provider may have closed it.")
                            }
                        }
                    }
                    else -> Unit
                }
            }

            // Black bars instead of the still peeking around a 4:3 picture.
            override fun onRenderedFirstFrame() {
                view[0]?.setBackgroundColor(Color.BLACK)
            }
        }
        player.addListener(listener)
        onDispose {
            stallCheck?.cancel()
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        // Clear until the first frame, so the still underneath shows through.
        factory = { LayoutInflater.from(it).inflate(R.layout.preview_player, null) as PlayerView },
        update = {
            view[0] = it
            if (it.player !== player) {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.player = player
            }
            it.keepScreenOn = keepsScreenOn
        },
        onRelease = {
            it.player = null
            view[0] = null
        },
    )
}
