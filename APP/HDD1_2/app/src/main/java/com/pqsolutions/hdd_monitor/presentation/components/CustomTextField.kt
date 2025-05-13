package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun CustomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    maxLines: Int = 1,
    minLines: Int = 1,
    maxLength: Int? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    supportingText: String? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            if (maxLength == null || newValue.length <= maxLength) {
                onValueChange(newValue)
            }
        },
        modifier = modifier.onFocusChanged {
            onFocusChanged?.invoke(it.isFocused)
        },
        label = label?.let { { Text(it) } },
        enabled = enabled,
        readOnly = readOnly,
        isError = isError,
        maxLines = maxLines,
        minLines = minLines,
        keyboardOptions = keyboardOptions.copy(
            imeAction = if (maxLines > 1) ImeAction.Default else ImeAction.Next
        ),
        keyboardActions = keyboardActions ?: KeyboardActions(
            onNext = {
                focusManager.moveFocus(FocusDirection.Down)
            },
            onDone = {
                keyboardController?.hide()
                focusManager.clearFocus()
            }
        ),
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        supportingText = supportingText?.let { { Text(it) } }
    )

    LaunchedEffect(value) {
        if (maxLength != null && value.length >= maxLength) {
            keyboardController?.hide()
        }
    }
}