package com.beauahill.hilltv.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.beauahill.hilltv.model.SyncServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The Sync screen: a QR code for a phone on the same Wi-Fi, the address and
 * code to type instead, and what the phone has done so far. The web server
 * runs only while this is on screen. The twin of SyncView.swift.
 *
 * Shown from `HillTVRoot`, above the login screen and the shell, so it stays
 * up when the phone signs this TV in and everything below it is rebuilt.
 */
@Composable
fun SyncScreen() {
    val app = LocalApp.current
    val sync = app.sync
    val view = LocalView.current

    DisposableEffect(sync) {
        sync.start()
        // The TV stays awake while the viewer is busy on the phone.
        view.keepScreenOn = true
        onDispose {
            sync.stop()
            view.keepScreenOn = false
        }
    }

    // The page stops working when the app leaves the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { app.syncShowing = false }

    // The newest Back handler wins. When the phone signs this TV in, the shell
    // is built underneath after this screen, and its own handler would take
    // Back and do nothing behind the cover. Registering again whenever the
    // screen underneath is rebuilt keeps this one the newest.
    key(app.client, app.phase, app.addingAccount) {
        BackHandler { app.syncShowing = false }
    }

    Box(Modifier.fillMaxSize().background(Theme.background).padding(40.dp), contentAlignment = Alignment.Center) {
        when (val status = sync.status) {
            SyncServer.Status.Stopped, SyncServer.Status.Starting -> Spinner()
            is SyncServer.Status.Failed -> SyncFailure(status.message, retry = { sync.start() }, close = { app.syncShowing = false })
            is SyncServer.Status.Ready -> SyncReady(status, sync, close = { app.syncShowing = false })
        }
    }
}

@Composable
private fun SyncReady(status: SyncServer.Status.Ready, sync: SyncServer, close: () -> Unit) {
    val done = rememberInitialFocus(status.code)
    Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        QRCode(
            status.url,
            Modifier
                .size(244.dp)
                .background(Color.White, RoundedCornerShape(Theme.radius))
                .padding(12.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("Sync")
            Txt("Scan with your phone", style = Type.title, color = Theme.text)
            Txt(
                "Point your phone's camera at the code. The phone must be on the same Wi-Fi as this TV. From there you can sign this TV in, or save and load its hidden categories, favorites and channel order.",
                Modifier.widthIn(max = 450.dp), Type.meta, Theme.muted,
            )

            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt("Or open this address and type the code", style = Type.small, color = Theme.muted)
                Txt(status.address, style = Type.body.copy(fontFamily = FontFamily.Monospace), color = Theme.text)
                Txt(
                    status.code.take(3) + " " + status.code.takeLast(3),
                    style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp),
                    color = Theme.text,
                    tabular = true,
                )
            }

            if (sync.isLocked) {
                Txt("Too many wrong codes. Close Sync and open it again to get a new code.", style = Type.meta, color = Theme.live)
            }

            if (sync.activity.isNotEmpty()) {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (line in sync.activity) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, size = 12.dp, tint = Theme.text)
                            Txt(line, style = Type.small, color = Theme.text, maxLines = 2)
                        }
                    }
                }
            }

            TVAction("Done", close, Modifier.padding(top = 6.dp).initialFocus(done), prominent = true)
        }
    }
}

@Composable
private fun SyncFailure(message: String, retry: () -> Unit, close: () -> Unit) {
    val first = rememberInitialFocus(message)
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.WifiOff, size = 42.dp, tint = Theme.muted)
        Txt("Sync isn't available", style = Type.title, color = Theme.text)
        Txt(message, Modifier.widthIn(max = 500.dp), Type.meta, Theme.muted, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TVAction("Try again", retry, Modifier.initialFocus(first), prominent = true)
            TVAction("Close", close)
        }
    }
}

/** A QR code drawn one pixel per module and scaled up without smoothing, so it stays sharp. */
@Composable
private fun QRCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix.get(index % matrix.width, index / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
}
