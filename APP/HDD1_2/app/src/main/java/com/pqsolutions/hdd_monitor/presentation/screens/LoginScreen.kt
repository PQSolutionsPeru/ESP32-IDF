package com.pqsolutions.hdd_monitor.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.presentation.components.HddButton
import com.pqsolutions.hdd_monitor.presentation.components.HddOutlinedTextField
import com.pqsolutions.hdd_monitor.presentation.util.Dimensions
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.util.playSoundEffect
import androidx.compose.animation.ExperimentalAnimationApi
import com.pqsolutions.hdd_monitor.presentation.components.ErrorText
import com.pqsolutions.hdd_monitor.presentation.components.LoadingButton
import com.pqsolutions.hdd_monitor.presentation.components.LoadingOverlay
import com.pqsolutions.hdd_monitor.presentation.viewmodel.LoginState
import com.pqsolutions.hdd_monitor.presentation.viewmodel.LoginViewModel

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun LoginScreen(
    loginViewModel: LoginViewModel,
    onLoginClick: (String, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    val loginState by loginViewModel.loginState.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimensions.paddingLarge),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = "App title" }
            )
            Spacer(modifier = Modifier.height(Dimensions.spacingLarge))
            HddOutlinedTextField(
                value = email,
                onValueChange = { if (loginState != LoginState.Loading) email = it },
                label = stringResource(R.string.email),
                modifier = Modifier.semantics { contentDescription = "Email input field" }
            )
            Spacer(modifier = Modifier.height(Dimensions.spacingMedium))
            HddOutlinedTextField(
                value = password,
                onValueChange = { if (loginState != LoginState.Loading) password = it },
                label = stringResource(R.string.password),
                isPassword = true,
                modifier = Modifier.semantics { contentDescription = "Password input field" }
            )
            Spacer(modifier = Modifier.height(Dimensions.spacingLarge))
            LoadingButton(
                onClick = {
                    performHapticFeedback(context)
                    loginViewModel.login(email, password)  // Cambiado para usar directamente el ViewModel
                },
                isLoading = loginState == LoginState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimensions.buttonHeight)
                    .semantics { contentDescription = "Login button" }
            ) {
                Text(stringResource(R.string.login))
            }

            if (loginState is LoginState.Error) {
                Spacer(modifier = Modifier.height(Dimensions.spacingMedium))
                ErrorText(error = (loginState as LoginState.Error).message)
            }
        }

        LoadingOverlay(
            isVisible = loginState == LoginState.Loading,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}