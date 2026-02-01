package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.domain.LoginUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val userRepository: UserRepository
) : ViewModel() {

    companion object {
        private const val TAG = "LoginViewModel"
    }

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState

    fun login(email: String, password: String) {
        viewModelScope.launch {
            try {
                _loginState.value = LoginState.Loading
                Log.d(TAG, "Iniciando login para: $email")

                val result = loginUseCase(LoginUseCase.Params(email, password))
                result.fold(
                    onSuccess = {
                        Log.d(TAG, "Login exitoso, actualizando token FCM")
                        updateFCMToken()
                        _loginState.value = LoginState.Success
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Error en login: ${e.message}")
                        _loginState.value = LoginState.Error(e.message ?: "Error desconocido")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Excepción durante login: ${e.message}")
                _loginState.value = LoginState.Error("Error de conexión: ${e.message}")
            }
        }
    }

    private suspend fun updateFCMToken() {
        try {
            // Primero, enviar cualquier token pendiente guardado anteriormente
            Log.d(TAG, "Verificando tokens FCM pendientes")
            userRepository.sendPendingFCMToken()
                .onSuccess {
                    Log.d(TAG, "Tokens pendientes procesados exitosamente")
                }
                .onFailure { e ->
                    Log.e(TAG, "Error procesando tokens pendientes: ${e.message}")
                }

            // Luego, obtener y actualizar el token actual
            val token = FirebaseMessaging.getInstance().token.await()
            Log.d(TAG, "Token FCM actual obtenido: $token")

            val currentUser = userRepository.getCurrentUser()
            if (currentUser != null) {
                Log.d(TAG, "Actualizando token actual para usuario: ${currentUser.email} (${currentUser.role})")
                userRepository.updateFcmToken(currentUser.documentName, token)
                    .onSuccess {
                        Log.d(TAG, "Token FCM actualizado exitosamente")
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Error actualizando token FCM: ${e.message}")
                    }
            } else {
                Log.e(TAG, "No se encontró información del usuario actual")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo token FCM: ${e.message}")
        }
    }

    fun resetState() {
        _loginState.value = LoginState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "LoginViewModel cleared")
    }
}

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: String) : LoginState()
}