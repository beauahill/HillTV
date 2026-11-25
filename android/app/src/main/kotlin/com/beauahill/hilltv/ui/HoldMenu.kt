package com.beauahill.hilltv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One line in a hold-Select menu. */
class MenuAction(val title: String, val action: () -> Unit)

class HoldMenu(val eyebrow: String, val title: String, val actions: List<MenuAction>)

/**
 * What holding Select on a channel, a game or a tile offers.
 *
 * The Apple TV app uses the system's context menu for this. Android TV has
 * no such thing, so the menu is a full-screen list built like the other
 * pickers. Picking a line closes the menu, then runs it; Back just closes it.
 */
class HoldMenuHost {
    var current: HoldMenu? by mutableStateOf(null)
        private set

    val isShowing: Boolean get() = current != null

    fun show(title: String, actions: List<MenuAction>, eyebrow: String = "Channel") {
        if (actions.isNotEmpty()) current = HoldMenu(eyebrow, title, actions)
    }

    fun close() {
        current = null
    }
}

val LocalHoldMenu = staticCompositionLocalOf<HoldMenuHost> { error("HoldMenuHost is not provided") }

@Composable
fun HoldMenuCover(host: HoldMenuHost) {
    val menu = host.current
    FullScreenCover(visible = menu != null, onDismiss = host::close) {
        if (menu != null) {
            val first = rememberInitialFocus(menu)
            CoverPage(menu.eyebrow, menu.title) {
                LazyColumn(
                    Modifier.widthIn(max = 600.dp),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(menu.actions) { index, item ->
                        TVRow(
                            onClick = {
                                // Close first: the action may open another page in the menu's place.
                                host.close()
                                item.action()
                            },
                            modifier = if (index == 0) Modifier.initialFocus(first) else Modifier,
                        ) {
                            Txt(item.title, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** "Add to Multiview", or "Open Multiview" for a channel that is already in it. */
internal fun multiviewAction(app: com.beauahill.hilltv.model.AppModel, stream: com.beauahill.hilltv.core.LiveStream) =
    MenuAction(if (app.multiview.tiles.contains(stream.id)) "Open Multiview" else "Add to Multiview") {
        app.multiview.addAndOpen(stream, app.multiviewLimit)
    }

internal fun favoriteAction(app: com.beauahill.hilltv.model.AppModel, stream: com.beauahill.hilltv.core.LiveStream): MenuAction {
    val isFavorite = app.favorites.contains(stream.id)
    return MenuAction(if (isFavorite) "Remove from Favorites" else "Add to Favorites") {
        app.favorites.toggle(stream)
        app.flash(if (isFavorite) "Removed from Favorites" else "Added to Favorites")
    }
}
