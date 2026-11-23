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
import com.beauahill.hilltv.model.AppModel

/**
 * A small live picture with no controls, for the Live TV detail pane.
 *
 * Always HLS, whatever the live format setting is. One call plays one URL:
 * a new channel gets a new player and the old one is torn down.
 */
@Composable
fun LivePreview(url: String, muted: Boolean, modifier: Modifier = Modifier, onFailure: () -> Unit = {}) {
    val context = LocalContext.current
    val failure by rememberUpdatedState(onFailure)

    val player = remember(url) {
        ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(mediaSourceFactory(context.applicationContext, AppModel.USER_AGENT))
            .build()
            .apply {
                // The pane is under half the screen; don't pull a 4K rendition for it.
                trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
                volume = if (muted) 0f else 1f
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
    }

    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }

    val view = remember { arrayOfNulls<PlayerView>(1) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = failure()

            // Black bars instead of the still peeking around a 4:3 picture.
            override fun onRenderedFirstFrame() {
                view[0]?.setBackgroundColor(Color.BLACK)
            }
        }
        player.addListener(listener)
        onDispose {
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
            // A preview left on screen shouldn't keep the TV awake.
            it.keepScreenOn = false
        },
        onRelease = {
            it.player = null
            view[0] = null
        },
    )
}
