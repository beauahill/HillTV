package com.beauahill.hilltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauahill.hilltv.model.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val serverField = remember { TVTextFieldState() }
    val usernameField = remember { TVTextFieldState() }
    val passwordField = remember { TVTextFieldState() }
    val signIn = remember { FocusRequester() }
    LaunchedEffect(Unit) { serverField.focus() }

    val canSubmit = !isWorking && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty()

    fun submit() {
        if (!canSubmit) return
        isWorking = true
        errorText = null
        scope.launch {
            try {
                app.signIn(server, username, password)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorText = error.readable()
            } finally {
                isWorking = false
            }
        }
    }

    Column(
        // Scrolls so the field being typed in stays above the keyboard.
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Wordmark(48.sp)
            Txt("Sign in with your Xtream Codes details", style = Type.meta, color = Theme.muted)
        }

        Column(
            Modifier.width(500.dp).background(Theme.surface, RoundedCornerShape(Theme.radius)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TVTextField(
                server, { server = it }, "Server, like http://line.example.com:8080",
                state = serverField, next = usernameField, keyboardType = KeyboardType.Uri,
            )
            TVTextField(username, { username = it }, "Username", state = usernameField, next = passwordField)
            TVTextField(password, { password = it }, "Password", state = passwordField, password = true) {
                try {
                    signIn.requestFocus()
                } catch (_: IllegalStateException) {
                }
            }
        }

        errorText?.let {
            Txt(it, Modifier.widthIn(max = 500.dp), Type.meta, Theme.live, textAlign = TextAlign.Center)
        }

        TVAction(onClick = { submit() }, Modifier.focusRequester(signIn), prominent = true, enabled = canSubmit) {
            Box(Modifier.widthIn(min = 150.dp), contentAlignment = Alignment.Center) {
                if (isWorking) Spinner(size = 16.dp, color = LocalContentColor.current) else Txt("Sign In")
            }
        }
    }
}
