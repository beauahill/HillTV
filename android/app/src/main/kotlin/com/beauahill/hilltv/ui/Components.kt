package com.beauahill.hilltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.XtreamCategory
import com.beauahill.hilltv.model.EPGStore
import com.beauahill.hilltv.model.LoadState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A picture from the network with something to show while it loads or when
 * the link is dead, which channel logos and posters often are.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!loaded) fallback()
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
                onSuccess = { loaded = true },
                onError = { loaded = false },
            )
        }
    }
}

/** Spinner / empty / error placeholder for a column or grid. */
@Composable
fun LoadStateView(
    state: LoadState,
    modifier: Modifier = Modifier,
    emptyText: String = "Nothing here",
    retry: (() -> Unit)? = null,
) {
    when (state) {
        LoadState.Idle, LoadState.Loaded -> Unit
        LoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
        LoadState.Empty -> Unavailable(Icons.Outlined.Inbox, emptyText, null, modifier)
        is LoadState.Failed -> Column(
            modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Unavailable(Icons.Outlined.Warning, "Couldn't load", state.message, Modifier)
            if (retry != null) TVAction("Try Again", retry, prominent = true)
        }
    }
}

/** An icon, a line and an optional explanation, centered. For "nothing to show here". */
@Composable
fun Unavailable(icon: ImageVector, title: String, detail: String?, modifier: Modifier = Modifier.fillMaxSize()) {
    Column(
        modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, size = 36.dp, tint = Theme.muted)
        Txt(title, Modifier.widthIn(max = 420.dp), Type.heading, Theme.muted, textAlign = TextAlign.Center)
        if (detail != null) Txt(detail, Modifier.widthIn(max = 420.dp), Type.meta, Theme.muted, textAlign = TextAlign.Center)
    }
}

/**
 * Left-hand category list shared by Guide, Movies and Series. Moving focus
 * onto a category selects it (debounced by the caller), so browsing is just
 * up/down on the remote.
 */
@Composable
fun CategoryColumn(
    title: String,
    categories: List<XtreamCategory>,
    selectedID: String?,
    modifier: Modifier = Modifier,
    onFocus: (XtreamCategory) -> Unit,
    onSelect: (XtreamCategory) -> Unit,
) {
    val selected = remember { FocusRequester() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(title, Modifier.padding(start = 18.dp))
        LazyColumn(
            // Coming back from the content lands on the selected category, not the nearest one.
            Modifier.focusRestorer(selected),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(categories, key = { it.id }) { category ->
                TVRow(
                    onClick = { onSelect(category) },
                    modifier = if (category.id == selectedID) Modifier.focusRequester(selected) else Modifier,
                    isSelected = category.id == selectedID,
                    minHeight = 32.dp,
                    textStyle = Type.meta,
                    onFocusChanged = { if (it) onFocus(category) },
                ) {
                    Txt(category.name, maxLines = 1)
                }
            }
        }
    }
}

/** Channel logo on a dark tile, with a fallback. Logos are frequently dead links. */
@Composable
fun ChannelLogo(url: String?, modifier: Modifier = Modifier, width: Dp = 48.dp, height: Dp = 32.dp) {
    Box(
        modifier
            .size(width, height)
            .background(Theme.control, RoundedCornerShape(5.dp))
            .padding(height * 0.12f),
    ) {
        RemoteImage(url, Modifier.fillMaxSize()) {
            Icon(Icons.Outlined.Tv, size = height * 0.4f, tint = Theme.muted)
        }
    }
}

@Composable
fun ArchiveMark(modifier: Modifier = Modifier, size: Dp = 13.dp) {
    Icon(Icons.Outlined.History, modifier, size, secondaryColor())
}

@Composable
fun FavoriteMark(modifier: Modifier = Modifier, size: Dp = 9.dp) {
    Icon(Icons.Filled.Favorite, modifier, size)
}

/** Poster tile for movies and series. Focus scales the artwork and draws an accent ring. */
@Composable
fun PosterCard(
    title: String,
    imageURL: String?,
    focused: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp = 110.dp,
    /** Watched share, drawn as a bar along the bottom of the artwork. */
    progress: Double? = null,
) {
    Column(modifier.width(width + 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.focusScaled(focused).focusRing(focused)) {
            Box(Modifier.size(width, width * 1.5f).clip(RoundedCornerShape(Theme.radius)).background(Theme.control)) {
                RemoteImage(imageURL, Modifier.fillMaxSize(), ContentScale.Crop) {
                    Icon(Icons.Outlined.Movie, size = 24.dp, tint = Theme.muted)
                }
                if (progress != null) {
                    ProgressBar(progress, Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth())
                }
            }
        }
        Column(Modifier.padding(horizontal = 4.5.dp).height(38.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, style = Type.small, color = Theme.text, maxLines = 2, weight = FontWeight.SemiBold)
            if (subtitle != null) Txt(subtitle, style = Type.small, color = Theme.muted, maxLines = 1)
        }
    }
}

/** Logo card for a live channel in a rail. */
@Composable
fun ChannelTile(stream: LiveStream, focused: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.width(139.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.focusScaled(focused).focusRing(focused, 5.dp)) {
            ChannelLogo(stream.iconURL, width = 130.dp, height = 80.dp)
        }
        Txt(stream.name, Modifier.padding(horizontal = 4.5.dp), Type.small, Theme.text, maxLines = 1, weight = FontWeight.SemiBold)
    }
}

/** A favorite channel on Home: logo, name, what's on now and how far along it is. */
@Composable
fun LiveNowCard(stream: LiveStream, epg: EPGStore, focused: Boolean, modifier: Modifier = Modifier) {
    val now = epg.now
    val schedule = epg.schedule(stream.id)
    val current = schedule?.current(now)

    Box(modifier.focusScaled(focused, 1.04f).focusRing(focused)) {
        Row(
            Modifier
                .size(230.dp, 70.dp)
                .background(if (focused) Theme.controlRaised else Theme.surface, RoundedCornerShape(Theme.radius))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(stream.iconURL, width = 55.dp, height = 37.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(stream.name, style = Type.meta, color = Theme.text, maxLines = 1, weight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (current != null) Box(Modifier.size(5.dp).background(Theme.live, CircleShape))
                    // A blank line holds the height until the listing arrives.
                    Txt(
                        current?.title ?: if (schedule == null) " " else "No guide data",
                        style = Type.small,
                        color = Theme.muted,
                        maxLines = 1,
                    )
                }
                ProgressBar(current?.progress(now) ?: 0.0, Modifier.fillMaxWidth().alpha(if (current == null) 0f else 1f))
            }
        }
    }
}

/** Titled horizontal row of tiles, used on Home and in Search. */
@Composable
fun Rail(title: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(title, Modifier.padding(horizontal = 20.dp), Type.heading, Theme.text)
        LazyRow(
            Modifier.focusRestorer(),
            // The padding sits inside the list's clip, so focused tiles can grow into it.
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}

/** Poster grid used by Movies and Series. */
@Composable
fun <T : Any> PosterGrid(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    tile: @Composable (T) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 119.dp),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items, key = key) { item -> tile(item) }
    }
}

/**
 * Lets a background run past its parent's edges to the edges of the screen,
 * under the rail and the safe margins a section is laid out inside.
 */
fun Modifier.bleed(): Modifier = layout { measurable, constraints ->
    val left = (Sidebar.width + 12.dp).roundToPx()
    val right = Theme.safeHorizontal.roundToPx()
    val vertical = Theme.safeVertical.roundToPx()
    val placeable = measurable.measure(
        Constraints.fixed(constraints.maxWidth + left + right, constraints.maxHeight + vertical * 2)
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-left, -vertical) }
}

/** Blurred, darkened artwork behind detail pages. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Theme.background)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(20.dp).alpha(0.4f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Theme.background.copy(alpha = 0.3f), Theme.background))
            )
        )
    }
}

/** A red dot and "Live", for the corner of a picture. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier, text: String = "Live", dot: Boolean = true) {
    Row(
        modifier.background(Theme.background.copy(alpha = 0.7f), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) Box(Modifier.size(6.dp).background(Theme.live, CircleShape))
        Txt(text, style = Type.small, color = Theme.text, maxLines = 1, weight = FontWeight.SemiBold)
    }
}

@Composable
fun Gap(width: Dp = 0.dp, height: Dp = 0.dp) {
    Spacer(Modifier.size(width, height))
}

/** A 16:9 box, for stills and scoreboards. */
fun Modifier.widescreen(): Modifier = aspectRatio(16f / 9f)

/** "1:04:12" or "34:12". */
fun clockString(seconds: Double): String {
    val total = if (seconds.isFinite()) maxOf(0, seconds.toInt()) else 0
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

/** Shown where a list of categories would be, when the user has hidden all of them. */
const val ALL_CATEGORIES_HIDDEN_TEXT = "Every category here is hidden. Show some in Settings > Categories."

/** "21 min left" or "1 h 5 min left". */
fun remainingLabel(minutes: Int): String {
    if (minutes < 60) return "$minutes min left"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "$hours h left" else "$hours h $rest min left"
}

private val shortTimeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val weekdayTimeFormat = DateTimeFormatter.ofPattern("EEE ")

/** "6:30 PM", in the viewer's time zone. */
val Instant.shortTime: String get() = atZone(ZoneId.systemDefault()).format(shortTimeFormat)

/** "6:30 PM" today, "Sun 1:00 PM" on another day. */
fun gameStartLabel(date: Instant): String {
    val zone = ZoneId.systemDefault()
    val local = date.atZone(zone)
    val today = Instant.now().atZone(zone).toLocalDate()
    return if (local.toLocalDate() == today) date.shortTime else local.format(weekdayTimeFormat) + date.shortTime
}
