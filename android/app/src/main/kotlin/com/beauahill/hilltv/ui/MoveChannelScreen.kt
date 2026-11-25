package com.beauahill.hilltv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.displayName

/** A channel the viewer is moving, and the list it is in. */
class ChannelMove(
    /** The list's name, shown as the heading. */
    val listTitle: String,
    /** The list as it is shown now. */
    val channels: List<LiveStream>,
    val stream: LiveStream,
)

/** Presents Move mode full screen whenever `move` is set. `onDrop` gets the position the channel was dropped at. */
@Composable
fun MoveChannelCover(move: ChannelMove?, onClose: () -> Unit, onDrop: (ChannelMove, Int) -> Unit) {
    FullScreenCover(visible = move != null, onDismiss = onClose) {
        if (move != null) {
            MoveChannelScreen(move) { index ->
                onDrop(move, index)
                onClose()
            }
        }
    }
}

/**
 * Move mode: the list, with the chosen channel riding on the focused row.
 *
 * Up and down are ordinary focus moves between rows. The rows are slots,
 * identified by position, and the moving channel is drawn in whichever slot
 * has focus, with the others closing up around it. Select drops it there,
 * and only there: a press on any other row brings the channel to that row
 * first. Back closes the page, like any full-screen cover, and nothing changes.
 */
@Composable
private fun MoveChannelScreen(move: ChannelMove, onDrop: (Int) -> Unit) {
    val start = remember(move) { move.channels.indexOfFirst { it.id == move.stream.id }.coerceAtLeast(0) }
    val others = remember(move) { move.channels.filter { it.id != move.stream.id } }
    val count = others.size + 1

    /** The slot the moving channel is drawn in. */
    var target by remember(move) { mutableStateOf(start) }

    /**
     * False until focus has been on the moving channel's row. Before that,
     * focus is wherever it first landed, which must not count as a move.
     */
    var armed by remember(move) { mutableStateOf(false) }

    // A row that isn't on screen can't take focus, so open with the channel's row in view.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val first = rememberInitialFocus(move)

    fun streamAt(slot: Int): LiveStream =
        if (slot == target) move.stream else others[if (slot < target) slot else slot - 1]

    CoverPage("Move channel", move.listTitle) {
        LazyColumn(
            Modifier.weight(1f).widthIn(max = 600.dp),
            state = list,
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(count, key = { it }) { slot ->
                TVRow(
                    onClick = {
                        if (slot == target) {
                            onDrop(slot)
                        } else {
                            // Focus never reached the moving row, so the channel isn't
                            // drawn here yet. Bring it here first; the next Select drops it.
                            target = slot
                            armed = true
                        }
                    },
                    modifier = if (slot == start) Modifier.initialFocus(first) else Modifier,
                    isSelected = slot == target,
                    minHeight = Theme.listRowHeight,
                    onFocusChanged = { focused ->
                        if (focused) {
                            if (slot == start) armed = true
                            if (armed) target = slot
                        }
                    },
                ) {
                    MoveRow(streamAt(slot), slot + 1, isMoving = slot == target)
                }
            }
        }
        Txt(
            "Up and down move it. Select drops it. Back cancels.",
            Modifier.padding(start = 18.dp), Type.small, Theme.muted,
        )
    }
}

@Composable
private fun RowScope.MoveRow(stream: LiveStream, position: Int, isMoving: Boolean) {
    Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterEnd) {
        if (isMoving) {
            Icon(Icons.Filled.SwapVert, size = 16.dp)
        } else {
            Txt(position.toString(), style = Type.meta, color = secondaryColor(), maxLines = 1, textAlign = TextAlign.End, tabular = true)
        }
    }
    Gap(width = 10.dp)
    ChannelLogo(stream.iconURL)
    ChannelNameLabel(stream.displayName, Modifier.padding(start = 10.dp).weight(1f), inFocusRow = true)
    if (isMoving) Txt("Moving", Modifier.padding(start = 6.dp), Type.small, maxLines = 1, weight = FontWeight.SemiBold)
}
