package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.UserData
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.util.playSoundEffect

@Composable
fun UserCard(
    user: UserData,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (user.role == UserRole.USER && user.clientName.isNotEmpty()) {
                    Text(
                        text = "Cliente: ${user.clientName}",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Text(
                    text = "Tipo de Cuenta: ${if (user.role == UserRole.ADMIN) "Administrador" else "Usuario"}",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (user.phone.isNotEmpty()) {
                    Text(
                        text = "Celular: ${user.phone}",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Row {
                IconButton(
                    onClick = {
                        performHapticFeedback(context)
                        onEditClick()
                    }
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.update),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = {
                        performHapticFeedback(context)
                        onDeleteClick()
                    }
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDialog(
    user: UserData? = null,
    clients: List<Client>,
    onCreateNewClient: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (UserData) -> Unit
) {
    var name by remember { mutableStateOf(user?.name ?: "") }
    var email by remember { mutableStateOf(user?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(user?.phone?.removePrefix("+51") ?: "") }
    var roleString by remember { mutableStateOf(user?.role?.let { UserRole.toFirestoreValue(it) } ?: "user") }
    var selectedClient by remember { mutableStateOf<Client?>(
        user?.clientDocName?.let { docName ->
            clients.find { it.documentName == docName }
        }
    ) }
    var showClientDropdown by remember { mutableStateOf(false) }

    // Validaciones
    val isPhoneValid = phone.isEmpty() || (phone.length == 9 && phone.all { it.isDigit() })
    val isEmailValid = email.contains("@") && email.contains(".")
    val isPasswordValid = user != null || password.length >= 6
    val isFormValid = when (UserRole.fromString(roleString)) {
        UserRole.ADMIN -> name.isNotBlank() && email.isNotBlank() && isEmailValid &&
                isPhoneValid && isPasswordValid
        UserRole.USER -> name.isNotBlank() && email.isNotBlank() && isEmailValid &&
                isPhoneValid && isPasswordValid && selectedClient != null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(if (user == null) R.string.create else R.string.update),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(
                        text = "Tipo de Cuenta",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = roleString == UserRole.toFirestoreValue(UserRole.USER),
                            onClick = {
                                roleString = UserRole.toFirestoreValue(UserRole.USER)
                                selectedClient = null
                            }
                        )
                        Text("Usuario")
                        Spacer(modifier = Modifier.weight(1f))
                        RadioButton(
                            selected = roleString == UserRole.toFirestoreValue(UserRole.ADMIN),
                            onClick = {
                                roleString = UserRole.toFirestoreValue(UserRole.ADMIN)
                                selectedClient = null
                            }
                        )
                        Text("Administrador")
                    }
                }

                if (roleString == UserRole.toFirestoreValue(UserRole.USER)) {
                    Column {
                        Text(
                            text = "Cliente",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedCard(
                                onClick = { showClientDropdown = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(selectedClient?.name ?: "Seleccionar Cliente")
                                    Icon(Icons.Default.ArrowDropDown, null)
                                }
                            }
                            Button(onClick = onCreateNewClient) {
                                Text("Nuevo")
                            }
                        }

                        DropdownMenu(
                            expanded = showClientDropdown,
                            onDismissRequest = { showClientDropdown = false }
                        ) {
                            clients.forEach { client ->
                                DropdownMenuItem(
                                    text = { Text(client.name) },
                                    onClick = {
                                        selectedClient = client
                                        showClientDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = name.isBlank()
                )

                TextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.email)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !isEmailValid && email.isNotBlank(),
                    supportingText = {
                        if (!isEmailValid && email.isNotBlank()) {
                            Text("Email inválido")
                        }
                    }
                )

                TextField(
                    value = phone,
                    onValueChange = { newValue ->
                        if (newValue.length <= 9 && newValue.all { it.isDigit() }) {
                            phone = newValue
                        }
                    },
                    label = { Text("Celular") },
                    placeholder = { Text("Ingrese 9 dígitos") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !isPhoneValid,
                    supportingText = {
                        if (!isPhoneValid) {
                            Text("Debe ingresar 9 dígitos")
                        }
                    }
                )

                if (user == null) {
                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = !isPasswordValid,
                        supportingText = {
                            if (!isPasswordValid) {
                                Text("La contraseña debe tener al menos 6 caracteres")
                            }
                        }
                    )
                }
            }
        },

        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        UserData(
                            documentName = user?.documentName ?: "",
                            email = email,
                            name = name,
                            roleString = roleString,
                            clientDocName = selectedClient?.documentName ?: "",
                            clientName = selectedClient?.name ?: "",
                            fcmToken = user?.fcmToken,
                            phone = if (phone.isNotBlank()) "+51$phone" else "",
                            password = password // Añadir la contraseña al objeto UserData
                        )
                    )
                },
                enabled = isFormValid
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ClientDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var clientName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Nuevo Cliente",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TextField(
                    value = clientName,
                    onValueChange = { clientName = it },
                    label = { Text("Nombre del Cliente") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(clientName) },
                enabled = clientName.isNotBlank()
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}