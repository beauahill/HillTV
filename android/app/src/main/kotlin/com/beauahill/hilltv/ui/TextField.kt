package com.beauahill.hilltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Lets one field hand typing on to the next, and lets a screen put focus on a field. */
class TVTextFieldState {
    internal var editing by mutableStateOf(false)
    internal val box = FocusRequester()

    /** Opens the keyboard on this field. */
    fun edit() {
        editing = true
    }

    /** Puts focus on the field without opening the keyboard. */
    fun focus() {
        try {
            box.requestFocus()
        } catch (_: IllegalStateException) {
            // Not on screen yet.
        }
    }
}

/**
 * A text field for a remote. Moving focus onto it does not open the
 * keyboard; Select does. The keyboard's Next key carries on into `next`.
 * Done, or moving focus away, ends typing.
 *
 * Done leaves focus on the field. To send it somewhere, do that in `onDone`
 * with a FocusRequester, not by moving "down": while the keyboard is up the
 * window is shorter and what is below may not be on screen to move to.
 */
@Composable
fun TVTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    state: TVTextFieldState = remember { TVTextFieldState() },
    next: TVTextFieldState? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    imeAction: ImeAction = if (next != null) ImeAction.Next else ImeAction.Done,
    onDone: () -> Unit = {},
) {
    val inner = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.editing) {
        if (state.editing) {
            try {
                inner.requestFocus()
                keyboard?.show()
            } catch (_: IllegalStateException) {
                state.editing = false
            }
        }
    }

    /**
     * Stops typing and leaves focus on the box, ready to move on. Focus moves
     * first: the field stops being focusable the moment typing ends, and
     * taking focus away from under it would send focus back to the top.
     */
    fun finish() {
        state.focus()
    }

    FocusBox(onClick = { state.edit() }, modifier.focusRequester(state.box)) { focused, _ ->
        val active = focused || state.editing
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(if (active) Theme.controlRaised else Theme.control, RoundedCornerShape(Theme.radius))
                .border(2.dp, if (active) Theme.accent else Color.Transparent, RoundedCornerShape(Theme.radius))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Txt(placeholder, style = Type.body, color = Theme.muted, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inner)
                    // Only reachable through Select on the box around it.
                    .focusProperties { canFocus = state.editing }
                    .onFocusChanged { if (!it.isFocused && state.editing) state.editing = false },
                textStyle = Type.body.copy(color = Theme.text),
                singleLine = true,
                cursorBrush = SolidColor(Theme.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(
                    onNext = {
                        if (next != null) next.edit() else finish()
                    },
                    onDone = {
                        keyboard?.hide()
                        finish()
                        onDone()
                    },
                    onSearch = {
                        keyboard?.hide()
                        finish()
                        onDone()
                    },
                ),
            )
        }
    }
}
