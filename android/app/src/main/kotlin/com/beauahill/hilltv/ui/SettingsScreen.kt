package com.beauahill.hilltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.beauahill.hilltv.core.CatalogKind
import com.beauahill.hilltv.core.CategoryGroup
import com.beauahill.hilltv.core.LiveFormat
import com.beauahill.hilltv.core.PlaybackSettings.LiveBuffer
import com.beauahill.hilltv.core.PlaybackSettings.LiveDelay
import com.beauahill.hilltv.core.XtreamCategory
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.model.AppModel
import com.beauahill.hilltv.model.CategoryVisibilityStore
import com.beauahill.hilltv.model.LivePreviewMode
import com.beauahill.hilltv.model.LoadState
import com.beauahill.hilltv.model.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val warningColor = Color(0xFFFBBF24)
private val expiryFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    var showingCategories by remember { mutableStateOf(false) }

    FullScreenCover(visible = showingCategories, onDismiss = { showingCategories = false }) {
        app.client?.let { CategoryManagerScreen(it, app.categoryVisibility) }
    }

    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxHeight()
            .widthIn(max = 600.dp)
            .verticalScroll(scroll)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("Settings")
            Txt("Preferences", style = Type.title, color = Theme.text)
        }

        SettingsCard("Account") {
            app.client?.account?.let { account ->
                InfoRow("Server", account.serverURL)
                InfoRow("Username", account.username)
            }
            app.authInfo?.user?.let { user ->
                InfoRow("Status", user.status ?: "Unknown")
                user.expiration?.let { InfoRow("Expires", it.atZone(ZoneId.systemDefault()).format(expiryFormat)) }
                user.maxConnections?.let { InfoRow("Connections", "${user.activeConnections ?: 0} of $it in use") }
                if (user.allowedOutputFormats.isNotEmpty()) InfoRow("Formats", user.allowedOutputFormats.joinToString(", "))
            }
            app.accountWarning?.let { Note(it, warningColor) }
        }

        SettingsCard("Live stream format") {
            for (format in LiveFormat.entries) {
                ChoiceRow(
                    formatTitle(format),
                    app.liveFormatChoice == format,
                    // The first thing on the page that takes focus. Nothing above it does,
                    // so coming back up to it scrolls the account details into view too.
                    onFocus = if (format == LiveFormat.entries.first()) ({ scope.launch { scroll.animateScrollTo(0) } }) else null,
                ) { app.liveFormatChoice = format }
            }
            Note(formatDetail(app.liveFormatChoice))
            if (app.liveFormat != app.liveFormatChoice) {
                Note("This provider doesn't offer that format, so live channels use ${formatTitle(app.liveFormat)}.", warningColor)
            }
        }

        SettingsCard("Live buffer") {
            for (buffer in LiveBuffer.entries) {
                ChoiceRow(bufferTitle(buffer), app.playback.liveBuffer == buffer) { app.playback = app.playback.copy(liveBuffer = buffer) }
            }
            Note("How much of a live channel is loaded ahead of what you're watching. Smaller starts a little faster. Larger rides out a weak connection. Movies and episodes buffer automatically.")
        }

        SettingsCard("Live delay") {
            for (delay in LiveDelay.entries) {
                ChoiceRow(delayTitle(delay), app.playback.liveDelay == delay) { app.playback = app.playback.copy(liveDelay = delay) }
            }
            Note(delayDetail(app.playback.liveDelay))
            Note("Most of the delay comes from how your provider packages the stream, which the app can't change. Live delay only affects HLS; MPEG-TS avoids that packaging altogether. Changes take effect the next time you start a channel.")
        }

        SettingsCard("Categories") {
            TVRow(onClick = { showingCategories = true }, textStyle = Type.meta) {
                Txt("Choose what to show", Modifier.weight(1f), maxLines = 1)
                Icon(Icons.Filled.ChevronRight, size = 14.dp)
            }
            Note(hiddenSummary(app))
        }

        SettingsCard("Live TV preview") {
            for (mode in LivePreviewMode.entries) {
                ChoiceRow(mode.displayName, app.previewMode == mode) { app.previewChoice = mode }
            }
            Note(previewNote(app))
        }

        SettingsCard("About") {
            InfoRow("Version", AppModel.USER_AGENT)
            InfoRow("Player", "Media3 ExoPlayer")
        }

        TVAction("Sign Out", onClick = { app.signOut() })
    }
}

private fun formatTitle(format: LiveFormat): String = when (format) {
    LiveFormat.HLS -> "HLS (.m3u8)"
    LiveFormat.MPEGTS -> "MPEG-TS (.ts)"
}

private fun formatDetail(format: LiveFormat): String = when (format) {
    LiveFormat.HLS ->
        "Live channels are requested as HLS, which plays almost anywhere but runs further behind the broadcast."
    LiveFormat.MPEGTS ->
        "Live channels play the raw stream, for less delay behind the broadcast. If channels stop or won't start, switch back to HLS."
}

private fun bufferTitle(buffer: LiveBuffer): String = when (buffer) {
    LiveBuffer.ONE_SECOND -> "1 second"
    LiveBuffer.TWO_SECONDS -> "2 seconds"
    LiveBuffer.STANDARD -> "Player's choice (default)"
    LiveBuffer.TEN_SECONDS -> "10 seconds"
    LiveBuffer.THIRTY_SECONDS -> "30 seconds"
}

private fun delayTitle(delay: LiveDelay): String = when (delay) {
    LiveDelay.STANDARD -> "Standard (default)"
    LiveDelay.KEEP_UP -> "Keep up"
    LiveDelay.CLOSEST -> "Closest to live"
}

private fun delayDetail(delay: LiveDelay): String = when (delay) {
    LiveDelay.STANDARD ->
        "The player decides how far behind the broadcast to play."
    LiveDelay.KEEP_UP ->
        "After the stream pauses to load, the player skips ahead to where it would have been, so the delay doesn't grow over time."
    LiveDelay.CLOSEST ->
        "Keeps up, and also plays at about half the usual distance behind the broadcast. Expect more pauses on a weak connection."
}

private fun hiddenSummary(app: AppModel): String {
    val counts = CatalogKind.entries.map { app.categoryVisibility.hiddenCount(it) }
    if (counts.all { it == 0 }) {
        return "Nothing is hidden. Hide the categories you never watch to shorten every list."
    }
    return "Hidden: ${counts[0]} live, ${counts[1]} movie and ${counts[2]} series categories. Saved on this device only."
}

private fun previewNote(app: AppModel): String {
    if (!app.previewSupported) {
        return "This provider doesn't offer HLS, so the preview can't play. Live TV shows a still instead."
    }
    var note = "Plays the focused channel in Live TV after a short pause. It uses one connection while it plays."
    if (app.previewChoice == null && !app.hasSpareConnection) {
        note += " Off for now because this account allows one connection."
    }
    return note
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, RoundedCornerShape(Theme.radius))
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(title, Modifier.padding(horizontal = 12.dp), Type.heading, Theme.text)
        content()
    }
}

/** One option in a list of choices, with a check mark on the chosen one. */
@Composable
private fun ChoiceRow(title: String, isChosen: Boolean, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    TVRow(onClick, isSelected = isChosen, textStyle = Type.meta, onFocusChanged = { if (it) onFocus?.invoke() }) {
        Txt(title, Modifier.weight(1f), maxLines = 1)
        if (isChosen) Icon(Icons.Filled.Check, size = 14.dp)
    }
}

@Composable
private fun Note(text: String, color: Color = Theme.muted) {
    Txt(text, Modifier.padding(horizontal = 12.dp), Type.small, color)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(label, style = Type.meta, color = Theme.muted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Txt(value, Modifier.widthIn(max = 380.dp), Type.meta, Theme.text, maxLines = 1)
    }
}

// MARK: - Categories

private fun kindTitle(kind: CatalogKind): String = when (kind) {
    CatalogKind.LIVE -> "Live TV"
    CatalogKind.MOVIES -> "Movies"
    CatalogKind.SERIES -> "Series"
}

private class CategoryBlock(val id: String, val title: String, val categories: List<XtreamCategory>)

/**
 * Full-screen list of every category the provider sends, where each one can
 * be hidden or shown. Opened from Settings. Live TV categories are grouped
 * the way the Live TV pills group them, so a whole group can go at once.
 */
@Composable
fun CategoryManagerScreen(client: XtreamClient, store: CategoryVisibilityStore) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(CatalogKind.LIVE) }
    val categories = remember { mutableStateMapOf<CatalogKind, List<XtreamCategory>>() }
    val states = remember { mutableStateMapOf<CatalogKind, LoadState>() }
    val firstTab = rememberInitialFocus()

    val current = categories[kind] ?: emptyList()
    val sections = remember(kind, current) {
        when (kind) {
            CatalogKind.LIVE -> CategoryGroup.grouping(current).map { CategoryBlock(it.group.id, it.group.title, it.categories) }
            else -> if (current.isEmpty()) emptyList() else listOf(CategoryBlock(kind.id, kindTitle(kind), current))
        }
    }

    suspend fun load(target: CatalogKind, force: Boolean) {
        if (!force && categories[target] != null) return
        states[target] = LoadState.Loading
        try {
            val result = when (target) {
                CatalogKind.LIVE -> client.liveCategories()
                CatalogKind.MOVIES -> client.vodCategories()
                CatalogKind.SERIES -> client.seriesCategories()
            }
            categories[target] = result
            states[target] = if (result.isEmpty()) LoadState.Empty else LoadState.Loaded
        } catch (error: CancellationException) {
            // Switching tabs cancels the request; it reloads when the tab comes back.
            throw error
        } catch (error: Exception) {
            states[target] = LoadState.Failed(error.readable())
        }
    }

    LaunchedEffect(kind) { load(kind, force = false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.background)
            .padding(horizontal = 40.dp, vertical = Theme.safeVertical + 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("Settings")
            Txt("Categories", style = Type.title, color = Theme.text)
            Txt(
                "Hidden categories disappear from Live TV, Guide, Movies, Series, Home and Search. Favorites stay.",
                style = Type.small, color = Theme.muted,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (item in CatalogKind.entries) {
                TVChip(
                    onClick = { kind = item },
                    modifier = if (item == CatalogKind.LIVE) Modifier.initialFocus(firstTab) else Modifier,
                    isSelected = item == kind,
                ) { Txt(kindTitle(item), maxLines = 1) }
            }
            Spacer(Modifier.weight(1f))
            Txt("${store.hiddenCount(kind)} of ${current.size} hidden", style = Type.small, color = Theme.muted, maxLines = 1, tabular = true)
            TVChip(onClick = { store.showAll(kind) }) { Txt("Show all", maxLines = 1) }
            TVChip(onClick = { store.setHidden(true, current.map { it.id }, kind) }) { Txt("Hide all", maxLines = 1) }
        }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (section in sections) {
                    val ids = section.categories.map { it.id }
                    item(key = "header-${section.id}") {
                        val hiddenHere = ids.count { store.isHidden(it, kind) }
                        val allHidden = hiddenHere == ids.size
                        Row(
                            Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(section.title, style = Type.heading, color = Theme.text)
                            Txt("${ids.size - hiddenHere} of ${ids.size} shown", style = Type.small, color = Theme.muted, tabular = true)
                            TVChip(onClick = { store.setHidden(!allHidden, ids, kind) }) {
                                Txt(if (allHidden) "Show group" else "Hide group", maxLines = 1)
                            }
                        }
                    }
                    // Three to a row, so a list of hundreds stays lazy.
                    items(section.categories.chunked(3), key = { row -> "${section.id}-${row.first().id}" }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (category in row) {
                                val hidden = store.isHidden(category.id, kind)
                                TVRow(
                                    onClick = { store.toggle(category.id, kind) },
                                    modifier = Modifier.weight(1f),
                                    minHeight = 32.dp,
                                    textStyle = Type.meta,
                                ) {
                                    Row(
                                        Modifier.alpha(if (hidden) 0.55f else 1f),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, size = 14.dp)
                                        Txt(
                                            category.name,
                                            style = LocalTextStyle.current.copy(textDecoration = if (hidden) TextDecoration.LineThrough else null),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            LoadStateView(states[kind] ?: LoadState.Loading, emptyText = "No categories") {
                scope.launch { load(kind, force = true) }
            }
        }
    }
}
