package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.UserData
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.util.playSoundEffect

private const val TAG = "ClientManagementDialogs"

@Composable
private fun ValidationError(error: String?) {
    if (error != null) {
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun PanelDialog(
    panel: Panel?,
    onDismiss: () -> Unit,
    onConfirm: (Panel) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(panel?.name ?: "") }
    var location by remember { mutableStateOf(panel?.location ?: "") }

    var nameError by remember { mutableStateOf<String?>(null) }
    var locationError by remember { mutableStateOf<String?>(null) }

    fun validateField(value: String, field: String): String? {
        return if (value.isBlank()) context.getString(R.string.error_required_field) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(if (panel == null) R.string.new_panel else R.string.edit_panel))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = validateField(it, "name")
                    },
                    label = { Text(stringResource(R.string.field_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = nameError != null,
                    supportingText = { ValidationError(nameError) },
                    singleLine = true
                )

                OutlinedTextField(
                    value = location,
                    onValueChange = {
                        location = it
                        locationError = validateField(it, "location")
                    },
                    label = { Text(stringResource(R.string.field_location)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = locationError != null,
                    supportingText = { ValidationError(locationError) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val isValid = nameError == null && locationError == null &&
                            name.isNotBlank() && location.isNotBlank()

                    if (isValid) {
                        performHapticFeedback(context)
                        onConfirm(
                            Panel(
                                documentName = panel?.documentName ?: "",
                                name = name.trim(),
                                location = location.trim(),
                                esp32_id = panel?.esp32_id ?: "",
                                clientName = panel?.clientName ?: ""
                            )
                        )
                    }
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    performHapticFeedback(context)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun UserDialog(
    user: UserData?,
    clientDocName: String,
    onDismiss: () -> Unit,
    onConfirm: (UserData) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(user?.name ?: "") }
    var email by remember { mutableStateOf(user?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    var nameError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    fun validateEmail(value: String): String? {
        return when {
            value.isBlank() -> context.getString(R.string.error_required_field)
            !android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches() ->
                context.getString(R.string.error_invalid_email)
            else -> null
        }
    }

    fun validatePassword(value: String): String? {
        return when {
            value.isBlank() -> context.getString(R.string.error_required_field)
            value.length < 6 -> context.getString(R.string.error_password_length)
            else -> null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(if (user == null) R.string.new_user else R.string.edit_user))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = if (it.isBlank()) context.getString(R.string.error_required_field) else null
                    },
                    label = { Text(stringResource(R.string.field_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = nameError != null,
                    supportingText = { ValidationError(nameError) },
                    singleLine = true
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        emailError = validateEmail(it)
                    },
                    label = { Text(stringResource(R.string.field_email)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = emailError != null,
                    supportingText = { ValidationError(emailError) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true
                )

                if (user == null) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            passwordError = validatePassword(it)
                        },
                        label = { Text(stringResource(R.string.field_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        isError = passwordError != null,
                        supportingText = { ValidationError(passwordError) },
                        visualTransformation = if (showPassword)
                            VisualTransformation.None
                        else
                            PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword)
                                        Icons.Default.Visibility
                                    else
                                        Icons.Default.VisibilityOff,
                                    contentDescription = if (showPassword)
                                        stringResource(R.string.hide_password)
                                    else
                                        stringResource(R.string.show_password)
                                )
                            }
                        },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val isValid = nameError == null && emailError == null &&
                            (user != null || passwordError == null) &&
                            name.isNotBlank() && email.isNotBlank() &&
                            (user != null || password.isNotBlank())

                    if (isValid) {
                        performHapticFeedback(context)
                        onConfirm(
                            UserData(
                                documentName = user?.documentName ?: "",
                                email = email.trim(),
                                name = name.trim(),
                                roleString = "user",
                                clientDocName = clientDocName,
                                fcmToken = user?.fcmToken
                            )
                        )
                    }
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    performHapticFeedback(context)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}