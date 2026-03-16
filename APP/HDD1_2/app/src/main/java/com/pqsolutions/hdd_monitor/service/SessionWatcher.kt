package com.pqsolutions.hdd_monitor.service

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import com.pqsolutions.hdd_monitor.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionWatcher @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val userPreferences: UserPreferences
) {
    companion object {
        private const val TAG = "SessionWatcher"
    }

    private var listenerRegistration: ListenerRegistration? = null

    fun start(userDocPath: String) {
        stop()
        Log.d(TAG, "Iniciando watcher de sesión en: $userDocPath")
        listenerRegistration = firestore.document(userDocPath)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Error en listener de sesión", error)
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val remoteToken = snapshot.getString("sessionToken")
                CoroutineScope(Dispatchers.IO).launch {
                    val localToken = userPreferences.getSessionToken()
                    if (!localToken.isNullOrEmpty() && !remoteToken.isNullOrEmpty() && remoteToken != localToken) {
                        Log.w(TAG, "Sesión invalidada: otro dispositivo inició sesión")
                        forceLogout()
                    }
                }
            }
    }

    fun stop() {
        listenerRegistration?.remove()
        listenerRegistration = null
        Log.d(TAG, "Watcher de sesión detenido")
    }

    private suspend fun forceLogout() {
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
        } catch (e: Exception) {
            Log.e(TAG, "Error eliminando token FCM", e)
        }
        userPreferences.clearUserData()
        auth.signOut()
        Log.d(TAG, "Sesión cerrada por sesión única")
    }
}
