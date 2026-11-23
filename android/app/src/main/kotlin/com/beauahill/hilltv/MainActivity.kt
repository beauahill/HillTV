package com.beauahill.hilltv

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.beauahill.hilltv.ui.HillTVRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = (application as HillTVApplication).model
        val root = BackFirstLayout(this) { onBackPressedDispatcher.onBackPressed() }
        root.addView(
            ComposeView(this).apply { setContent { HillTVRoot(model) } },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
    }
}

/**
 * Takes the Back key before the on-screen keyboard's service sees it.
 *
 * Key presses go to the keyboard service first. On a TV it can decide it has
 * something to close when it has nothing on screen, and swallow the press:
 * seen on the Android TV emulator, where the first Back after the video view
 * took focus did nothing and the player needed a second press. So unless the
 * keyboard really is showing (then Back should close it), Back is handled
 * here, straight away.
 */
private class BackFirstLayout(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !keyboardShowing()) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEventPreIme(event)
    }

    private fun keyboardShowing(): Boolean =
        ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
}
