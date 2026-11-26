package com.beauahill.hilltv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauahill.hilltv.core.AccountBook
import com.beauahill.hilltv.core.XtreamAccount
import com.beauahill.hilltv.core.host
import com.beauahill.hilltv.core.initial
import com.beauahill.hilltv.core.label

/** What the account switcher asked for. Carried out by `MainShell` once the page has closed. */
sealed interface AccountAction {
    data class SwitchTo(val key: String) : AccountAction
    data class Remove(val key: String) : AccountAction
    data object Add : AccountAction
}

/**
 * The account switcher the rail's badge opens: every saved login, the one in
 * use marked, and Add an account. Select switches; hold Select offers Remove.
 * The twin of AccountSwitcher.swift.
 *
 * A full-screen page, so focus goes into it by itself. Switching rebuilds the
 * shell that opened it, so the choice is handed back and carried out once the
 * page has closed.
 */
@Composable
fun AccountSwitcher(onChoose: (AccountAction) -> Unit) {
    val app = LocalApp.current
    val menus = LocalHoldMenu.current
    val first = rememberInitialFocus()
    val focusKey = app.currentAccountKey

    CoverPage("Accounts", "Switch account") {
        LazyColumn(
            Modifier.weight(1f, fill = false).widthIn(max = 550.dp),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(app.accounts, key = { AccountBook.key(it) }) { account ->
                val key = AccountBook.key(account)
                val inUse = key == app.currentAccountKey
                val connections = app.accountDetails(key)?.user?.maxConnections
                TVRow(
                    onClick = { onChoose(AccountAction.SwitchTo(key)) },
                    modifier = if (key == focusKey) Modifier.initialFocus(first) else Modifier,
                    isSelected = inUse,
                    minHeight = Theme.listRowHeight,
                    textStyle = Type.meta,
                    onLongClick = {
                        menus.show(
                            account.label,
                            listOf(MenuAction("Remove from this TV") { onChoose(AccountAction.Remove(key)) }),
                            eyebrow = "Account",
                        )
                    },
                ) {
                    AccountInitial(account, 28.dp)
                    Column(Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Txt(account.username, maxLines = 1, weight = FontWeight.SemiBold)
                        Txt(account.host ?: account.serverURL, style = Type.small, color = secondaryColor(), maxLines = 1)
                    }
                    if (connections != null) {
                        Txt(
                            if (connections == 1) "1 connection" else "$connections connections",
                            Modifier.padding(start = 8.dp), Type.small, secondaryColor(), maxLines = 1,
                        )
                    }
                    if (inUse) Txt("In use", Modifier.padding(start = 10.dp), Type.small, maxLines = 1, weight = FontWeight.SemiBold)
                }
            }
            item(key = "add") {
                TVRow(
                    onClick = { onChoose(AccountAction.Add) },
                    modifier = if (focusKey == null) Modifier.initialFocus(first) else Modifier,
                    minHeight = Theme.listRowHeight,
                    textStyle = Type.meta,
                ) {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, size = 16.dp) }
                    Txt("Add an account", Modifier.padding(start = 10.dp), maxLines = 1)
                }
            }
        }
        Txt("Hold Select on an account to remove it from this TV.", Modifier.padding(start = 18.dp), Type.small, Theme.muted)
    }
}

/** An account's first letter in a circle, drawn in the colour around it. */
@Composable
fun AccountInitial(account: XtreamAccount?, size: Dp, color: Color = Color.Unspecified) {
    val tint = if (color == Color.Unspecified) LocalContentColor.current else color
    Box(Modifier.size(size).border(1.25.dp, tint, CircleShape), contentAlignment = Alignment.Center) {
        Txt(
            account?.initial ?: "?",
            style = TextStyle(fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.Bold),
            color = tint,
            maxLines = 1,
        )
    }
}

