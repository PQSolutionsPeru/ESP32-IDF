package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.presentation.util.Dimensions
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.util.playSoundEffect

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = stringResource(R.string.confirm),
    dismissText: String = stringResource(R.string.cancel),
    icon: @Composable (() -> Unit)? = null,
    confirmButtonColors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary
    ),
    isDestructive: Boolean = false
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    performHapticFeedback(context)
                    onConfirm()
                },
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                } else confirmButtonColors
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    performHapticFeedback(context)
                    onDismiss()
                }
            ) {
                Text(dismissText)
            }
        }
    )
}

@Composable
fun DeleteConfirmationDialog(
    itemType: String,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ConfirmationDialog(
        title = stringResource(R.string.confirm_deletion),
        message = stringResource(R.string.delete_confirmation_message, itemType),
        onConfirm = onConfirmDelete,
        onDismiss = onDismiss,
        confirmText = stringResource(R.string.delete),
        isDestructive = true
    )
}

@Composable
fun AcceptEventDialog(
    onConfirmAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    ConfirmationDialog(
        title = stringResource(R.string.confirm_acceptance),
        message = stringResource(R.string.accept_event_confirmation_message),
        onConfirm = onConfirmAccept,
        onDismiss = onDismiss,
        confirmText = stringResource(R.string.accept)
    )
}

@Composable
fun UnsavedChangesDialog(
    onConfirmDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    ConfirmationDialog(
        title = stringResource(R.string.unsaved_changes),
        message = stringResource(R.string.unsaved_changes_message),
        onConfirm = onConfirmDiscard,
        onDismiss = onDismiss,
        confirmText = stringResource(R.string.discard),
        dismissText = stringResource(R.string.keep_editing),
        isDestructive = true
    )
}

@Composable
fun LoadingDialog(
    message: String = stringResource(R.string.please_wait)
) {
    AlertDialog(
        onDismissRequest = { /* No dismissible */ },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimensions.paddingMedium),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = { }
    )
}

@Composable
fun ErrorDialog(
    title: String = stringResource(R.string.error),
    message: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title)
        },
        text = {
            Text(message)
        },
        confirmButton = {
            if (onRetry != null) {
                Button(
                    onClick = {
                        performHapticFeedback(context)
                        onRetry()
                    }
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    performHapticFeedback(context)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

// Estado para manejar múltiples diálogos
class DialogState {
    var currentDialog by mutableStateOf<@Composable (() -> Unit)?>(null)
        private set

    fun show(content: @Composable () -> Unit) {
        currentDialog = content
    }

    fun dismiss() {
        currentDialog = null
    }
}

@Composable
fun rememberDialogState() = remember { DialogState() }

// Componente para mostrar el diálogo actual
@Composable
fun DialogHost(dialogState: DialogState) {
    dialogState.currentDialog?.invoke()
}