package com.beauahill.hilltv.ui

import android.graphics.Typeface
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens, the Android twin of `App/Views/Theme.swift`.
 *
 * The Apple TV design is drawn on a 1920 x 1080 point canvas. A 1080p
 * Android TV is 960 x 540 dp, so every size here is the tvOS number halved.
 * On a 1080p panel both apps then draw the same pixels.
 *
 * - Dark, blue-tinted surfaces.
 * - Blue marks focus, selection and progress. Red is only for "live".
 * - Sans throughout. The big display size is condensed and bold.
 * - Nothing smaller than 10 sp (20 pt on the Apple TV).
 */
object Theme {
    val background = Color(0xFF0D1117)
    val sidebar = Color(0xFF0A0D13)
    val surface = Color(0xFF151B26)
    val control = Color(0xFF1D2636)
    val controlRaised = Color(0xFF283448)
    val text = Color(0xFFE8ECF4)
    val muted = Color(0xFFA6B0C2)
    val accent = Color(0xFF5B9BFF)
    val onAccent = Color(0xFF07101F)
    val live = Color(0xFFE5484D)
    val line = Color(0xFF1F2836)

    val radius = 7.dp
    val focusRing = 2.5.dp
    const val focusScale = 1.06f
    val selectedBar = 2.5.dp
    val railWidth = 52.dp
    val railExpandedWidth = 170.dp
    val rowHeight = 36.dp
    val listRowHeight = 48.dp

    /** Room left around the picture for TVs that crop the edges. */
    val safeHorizontal = 28.dp
    val safeVertical = 20.dp

    private val easeOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    fun <T> focusAnimation() = tween<T>(durationMillis = 160, easing = easeOut)
}

object Type {
    private val condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

    val display = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.Bold, fontFamily = condensed, lineHeight = 40.sp)
    val title = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
    val subtitle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
    val heading = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 18.sp)
    val meta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val small = TextStyle(fontSize = 10.sp, lineHeight = 13.sp)
    val eyebrow = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)

    /** Digits that don't shift as they change: scores, clocks, channel numbers. */
    const val tabular = "tnum"

    val condensedFamily: FontFamily get() = condensed
}

internal val ellipsis = TextOverflow.Ellipsis
