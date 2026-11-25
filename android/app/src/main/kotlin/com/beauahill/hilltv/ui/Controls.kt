package com.beauahill.hilltv.ui

import android.view.ViewConfiguration
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

// Text, icons and the focusable controls every screen is built from.
// The Android twin of the button styles in `App/Views/Theme.swift`.

/** True inside a control that has focus. The control fills blue then, so its contents flip to dark. */
val LocalFocused = compositionLocalOf { false }
val LocalContentColor = compositionLocalOf { Theme.text }
val LocalTextStyle = compositionLocalOf { Type.body }

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Unspecified,
    weight: FontWeight? = null,
    tabular: Boolean = false,
    /** Lines to keep room for, so what is below doesn't move when the text is shorter. */
    minLines: Int = 1,
) {
    val resolved = style.merge(
        TextStyle(
            color = color.takeOrElse { LocalContentColor.current },
            textAlign = textAlign,
            fontWeight = weight,
            fontFeatureSettings = if (tabular) Type.tabular else null,
        )
    )
    BasicText(text, modifier, resolved, overflow = ellipsis, maxLines = maxLines, minLines = minLines, softWrap = maxLines != 1)
}

@Composable
fun Icon(image: ImageVector, modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = Color.Unspecified) {
    Image(
        painter = rememberVectorPainter(image),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint.takeOrElse { LocalContentColor.current }),
    )
}

/** Muted text that stays readable when its row takes focus. */
@Composable
fun secondaryColor(): Color = if (LocalFocused.current) Theme.onAccent.copy(alpha = 0.75f) else Theme.muted

private val selectKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)

/**
 * Which control had focus last. A page that gets covered (by the player, a
 * detail page, a picker) reads this before the cover takes focus, and hands
 * focus back to the same control when the cover closes.
 */
object FocusMemory {
    var last: FocusRequester? = null
}

/**
 * The one focusable building block. Select (or Enter, or a tap) clicks.
 * Holding Select calls `onLongClick`, the remote's version of a long press.
 * `content` is told whether it has focus so it can draw its own highlight.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.(focused: Boolean, pressed: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val focusCallback by rememberUpdatedState(onFocusChanged)
    val self = remember { FocusRequester() }
    val longPressMillis = remember { ViewConfiguration.getLongPressTimeout().toLong() }
    // Whether the hold already fired, so letting go doesn't click as well.
    val held = remember { booleanArrayOf(false) }

    Box(
        modifier
            .focusRequester(self)
            .onFocusChanged {
                if (it.isFocused) FocusMemory.last = self
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    if (!it.isFocused) pressed = false
                    focusCallback?.invoke(it.isFocused)
                }
            }
            .onPreviewKeyEvent { event ->
                // Only when the box itself has focus. With focus on something inside it
                // (a text field being typed in), the keys belong to that.
                if (!enabled || !focused || event.key !in selectKeys) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (native.repeatCount == 0) {
                            pressed = true
                            held[0] = false
                        } else if (pressed && !held[0] && longClick != null &&
                            native.eventTime - native.downTime >= longPressMillis
                        ) {
                            held[0] = true
                            longClick?.invoke()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        // A key-up with no key-down here belongs to whatever had focus before.
                        if (pressed && !held[0]) click()
                        pressed = false
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onLongPress = { longClick?.invoke() },
                        onTap = { click() },
                    )
                }
            },
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalFocused provides focused) {
            content(focused, pressed)
        }
    }
}

/** Asks for focus once, when the element first appears. For the first control on a page or a cover. */
@Composable
fun rememberInitialFocus(key: Any? = Unit): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) {
        try {
            requester.requestFocus()
        } catch (_: IllegalStateException) {
            // Not laid out yet. Whatever takes focus next is fine.
        }
    }
    return requester
}

fun Modifier.initialFocus(requester: FocusRequester?): Modifier =
    if (requester == null) this else focusRequester(requester)

@Composable
private fun focusScale(focused: Boolean, scale: Float): Float {
    val value by animateFloatAsState(if (focused) scale else 1f, Theme.focusAnimation(), label = "focusScale")
    return value
}

private val rowShape = RoundedCornerShape(Theme.radius)

/**
 * Categories, channels, episodes, settings choices.
 * Focus: blue fill with dark text. Selected: a blue bar on the leading edge.
 */
@Composable
fun TVRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    minHeight: Dp = Theme.rowHeight,
    textStyle: TextStyle = Type.body,
    onLongClick: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    FocusBox(onClick, modifier.fillMaxWidth(), onLongClick = onLongClick, onFocusChanged = onFocusChanged) { focused, pressed ->
        val scale = focusScale(focused, 1.02f)
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (pressed) 0.85f else 1f
                }
                .background(
                    when {
                        focused -> Theme.accent
                        isSelected -> Theme.control
                        else -> Color.Transparent
                    },
                    rowShape,
                )
                .defaultMinSize(minHeight = minHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (isSelected && !focused) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(vertical = 7.dp)
                        .width(Theme.selectedBar)
                        .height(minHeight - 14.dp)
                        .background(Theme.accent, CircleShape)
                )
            }
            CompositionLocalProvider(
                LocalContentColor provides if (focused) Theme.onAccent else Theme.text,
                LocalTextStyle provides textStyle,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
    }
}

/**
 * Standalone actions and chips: Watch, Sign In, Try Again, seasons.
 * Prominent buttons are light at rest; every button turns blue with a ring on focus.
 */
@Composable
fun TVAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    isSelected: Boolean = false,
    enabled: Boolean = true,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    FocusBox(onClick, modifier, enabled = enabled, onFocusChanged = onFocusChanged) { focused, pressed ->
        val fill = when {
            focused -> Theme.accent
            prominent -> Theme.text
            else -> Theme.control
        }
        val foreground = when {
            focused -> Theme.onAccent
            prominent -> Theme.background
            else -> Theme.text
        }
        Box(
            Modifier
                .alpha(if (!enabled) 0.4f else if (pressed) 0.85f else 1f)
                .then(
                    if (focused) Modifier.border(2.dp, Theme.accent, RoundedCornerShape(Theme.radius + 4.dp)) else Modifier
                )
                .padding(4.5.dp)
                .background(fill, rowShape)
                .defaultMinSize(minHeight = Theme.rowHeight),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides foreground,
                LocalTextStyle provides Type.meta.copy(fontWeight = FontWeight.SemiBold),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
            if (isSelected && !focused) {
                // Sized to the button, not to the row it sits in.
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .padding(horizontal = 9.dp)
                            .fillMaxWidth()
                            .height(Theme.selectedBar)
                            .background(Theme.accent, CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
fun TVAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, prominent: Boolean = false, isSelected: Boolean = false, enabled: Boolean = true) {
    TVAction(onClick, modifier, prominent, isSelected, enabled) { Txt(text, maxLines = 1) }
}

@Composable
private fun Capsule(
    onClick: () -> Unit,
    modifier: Modifier,
    isSelected: Boolean,
    minHeight: Dp,
    horizontalPadding: Dp,
    textStyle: TextStyle,
    scale: Float,
    onFocusChanged: ((Boolean) -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    FocusBox(onClick, modifier, onFocusChanged = onFocusChanged) { focused, pressed ->
        val animated = focusScale(focused, scale)
        Box(
            Modifier
                .zIndex(if (focused) 1f else 0f)
                .graphicsLayer {
                    scaleX = animated
                    scaleY = animated
                    alpha = if (pressed) 0.85f else 1f
                }
                .background(
                    when {
                        focused -> Theme.accent
                        isSelected -> Theme.accent.copy(alpha = 0.2f)
                        else -> Theme.control
                    },
                    CircleShape,
                )
                .defaultMinSize(minHeight = minHeight),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides when {
                    focused -> Theme.onAccent
                    isSelected -> Theme.accent
                    else -> Theme.text
                },
                LocalTextStyle provides textStyle,
            ) {
                Row(
                    Modifier.padding(horizontal = horizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
    }
}

/**
 * Large capsule for the top-level choices on a screen, such as the Live TV
 * groups. Focus: blue fill with dark text. Selected: blue tint with blue text.
 */
@Composable
fun TVPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Capsule(onClick, modifier, isSelected, 46.dp, 19.dp, TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), 1.05f, onFocusChanged, content)
}

/**
 * Compact capsule for filters, such as Live TV categories.
 * Focus: blue fill with dark text. Selected: blue tint with blue text.
 */
@Composable
fun TVChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Capsule(onClick, modifier, isSelected, 28.dp, 13.dp, Type.eyebrow, 1.06f, onFocusChanged, content)
}

/** No chrome of its own. The content draws focus itself (see `PosterCard`). */
@Composable
fun TVCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    FocusBox(onClick, modifier.zIndex(0f), onLongClick = onLongClick, onFocusChanged = onFocusChanged) { focused, pressed ->
        Box(Modifier.alpha(if (pressed) 0.85f else 1f)) { content(focused) }
    }
}

/** The blue ring cards draw around their artwork when focused. */
fun Modifier.focusRing(focused: Boolean, radius: Dp = Theme.radius): Modifier =
    if (focused) {
        border(Theme.focusRing, Theme.accent, RoundedCornerShape(radius + 3.5.dp)).padding(4.5.dp)
    } else {
        padding(4.5.dp)
    }

/** Grows a card a little when it has focus, and lifts it over its neighbors. */
@Composable
fun Modifier.focusScaled(focused: Boolean, scale: Float = Theme.focusScale): Modifier {
    val animated = focusScale(focused, scale)
    return zIndex(if (focused) 1f else 0f).graphicsLayer {
        scaleX = animated
        scaleY = animated
    }
}

// MARK: - Small building blocks

/** Small uppercase label above titles and sections. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Theme.accent) {
    Txt(text.uppercase(), modifier, Type.eyebrow.copy(letterSpacing = 1.5.sp), color, maxLines = 1)
}

/** Rounded metadata chip: year, genre, rating, duration. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Txt(
        text,
        modifier.background(Theme.control, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
        Type.small,
        Theme.text,
        maxLines = 1,
        weight = FontWeight.Medium,
    )
}

/** Thin progress bar on a dark track. */
@Composable
fun ProgressBar(value: Double, modifier: Modifier = Modifier, height: Dp = 4.dp, track: Color = Theme.control, fill: Color = Theme.accent) {
    Box(modifier.height(height).clip(CircleShape).background(track)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(value.coerceIn(0.0, 1.0).toFloat())
                .background(fill, CircleShape)
        )
    }
}

/**
 * Progress bar for use inside a focusable row or card whose fill turns blue
 * on focus. It flips to dark there so it stays visible.
 */
@Composable
fun FocusAwareProgressBar(value: Double, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val focused = LocalFocused.current
    ProgressBar(
        value,
        modifier,
        height,
        track = if (focused) Theme.onAccent.copy(alpha = 0.2f) else Theme.control,
        fill = if (focused) Theme.onAccent else Theme.accent,
    )
}

/** A turning arc, for anything that is loading. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Theme.accent) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.12f
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
