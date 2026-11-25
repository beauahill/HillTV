package com.beauahill.hilltv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.core.displayName

/** A plain channel row: number, logo, name. */
@Composable
internal fun RowScope.ChannelRow(stream: LiveStream, isFavorite: Boolean) {
    stream.number?.let {
        Txt(it.toString(), Modifier.width(32.dp), Type.meta, secondaryColor(), maxLines = 1, textAlign = TextAlign.End, tabular = true)
        Gap(width = 10.dp)
    }
    ChannelLogo(stream.iconURL)
    ChannelNameLabel(stream.displayName, Modifier.padding(start = 10.dp).weight(1f), inFocusRow = true)
    if (stream.hasArchive) ArchiveMark(Modifier.padding(start = 6.dp))
    if (isFavorite) FavoriteMark(Modifier.padding(start = 6.dp), size = 12.dp)
}

@Composable
fun FavoritesScreen(client: XtreamClient) {
    val app = LocalApp.current
    val menus = LocalHoldMenu.current
    val channels = app.favorites.channels
    var moving by remember { mutableStateOf<ChannelMove?>(null) }

    MoveChannelCover(moving, onClose = { moving = null }) { move, index ->
        app.favorites.move(move.stream.id, index)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("Favorites")
            Txt("Your channels", style = Type.title, color = Theme.text)
        }

        if (channels.isEmpty()) {
            Unavailable(
                Icons.Outlined.FavoriteBorder,
                "No favorites yet",
                "In Live TV, press and hold Select on a channel to add it.",
            )
        } else {
            LazyColumn(
                Modifier.widthIn(max = 600.dp).focusRestorer(),
                contentPadding = PaddingValues(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(channels, key = { it.id }) { stream ->
                    TVRow(
                        onClick = { app.play(liveItem(stream, client, app)) },
                        minHeight = Theme.listRowHeight,
                        onLongClick = {
                            menus.show(
                                stream.displayName.title,
                                buildList {
                                    add(multiviewAction(app, stream))
                                    if (channels.size > 1) add(MenuAction("Move") { moving = ChannelMove("Favorites", channels, stream) })
                                    add(favoriteAction(app, stream))
                                },
                            )
                        },
                    ) {
                        ChannelRow(stream, isFavorite = false)
                    }
                }
            }
        }
    }
}
