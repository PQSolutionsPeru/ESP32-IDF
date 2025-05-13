package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.pqsolutions.hdd_monitor.data.util.IdManager
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    companion object {
        private const val TAG = "UserRepository"
        private const val BASE_PATH = "hdd-monitor/accounts"
    }

    suspend fun getClients(): Result<List<Client>> = withContext(Dispatchers.IO) {
        try {
            val clientsQuery = firestore.collection("$BASE_PATH/clients").get().await()
            val clients = clientsQuery.documents.mapNotNull { doc ->
                if (doc.exists()) {
                    Client(
                        documentName = doc.id,
                        name = doc.getString("name") ?: ""
                    )
                } else null
            }
            Result.success(clients)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting clients: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createClient(client: Client): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val documentName = IdManager.generateClientDocumentName(client.name)
            val clientData = mapOf(
                "documentName" to documentName,
                "name" to client.name
            )

            firestore.document("$BASE_PATH/clients/$documentName")
                .set(clientData)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating client: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateClient(client: Client): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (client.documentName.isEmpty()) {
                throw IllegalArgumentException("Client document name cannot be empty")
            }

            val clientData = mapOf(
                "documentName" to client.documentName,
                "name" to client.name
            )

            firestore.document("$BASE_PATH/clients/${client.documentName}")
                .set(clientData)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating client: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteClient(clientDocName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (clientDocName.isEmpty()) {
                throw IllegalArgumentException("Client document name cannot be empty")
            }

            val clientRef = firestore.document("$BASE_PATH/clients/$clientDocName")
            val batch = firestore.batch()

            // Eliminar todos los usuarios del cliente
            val usersSnapshot = clientRef.collection("users").get().await()
            usersSnapshot.documents.forEach { doc ->
                batch.delete(doc.reference)
            }

            // Eliminar todos los paneles y sus subcolecciones
            val panelsSnapshot = clientRef.collection("panels").get().await()
            panelsSnapshot.documents.forEach { panelDoc ->
                // Eliminar las subcolecciones de cada panel
                val relaysSnapshot = panelDoc.reference.collection("relays").get().await()
                relaysSnapshot.documents.forEach { doc ->
                    batch.delete(doc.reference)
                }

                val panelEventsSnapshot = panelDoc.reference.collection("panel_events").get().await()
                panelEventsSnapshot.documents.forEach { doc ->
                    batch.delete(doc.reference)
                }

                val panelEventsLogSnapshot = panelDoc.reference.collection("panel_events_log").get().await()
                panelEventsLogSnapshot.documents.forEach { doc ->
                    batch.delete(doc.reference)
                }

                // Eliminar el panel
                batch.delete(panelDoc.reference)
            }

            // Eliminar las notificaciones del cliente
            val notificationsSnapshot = clientRef.collection("notifications").get().await()
            notificationsSnapshot.documents.forEach { doc ->
                batch.delete(doc.reference)
            }

            // Eliminar los eventos del cliente
            val eventsSnapshot = clientRef.collection("events").get().await()
            eventsSnapshot.documents.forEach { doc ->
                batch.delete(doc.reference)
            }

            // Finalmente eliminar el documento del cliente
            batch.delete(clientRef)

            // Ejecutar todas las operaciones en batch
            batch.commit().await()

            Log.d(TAG, "Client and all related documents deleted successfully: $clientDocName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting client: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getUsers(): Result<List<UserData>> = withContext(Dispatchers.IO) {
        try {
            val users = mutableListOf<UserData>()

            // Obtener todos los clientes
            val clientsQuery = firestore.collection("$BASE_PATH/clients").get().await()

            // Para cada cliente, obtener sus usuarios
            clientsQuery.documents.forEach { clientDoc ->
                val clientName = clientDoc.getString("name") ?: clientDoc.id
                val usersQuery = clientDoc.reference.collection("users").get().await()

                val clientUsers = usersQuery.documents.mapNotNull { userDoc ->
                    if (userDoc.exists()) {
                        UserData(
                            documentName = userDoc.id,
                            email = userDoc.getString("email") ?: "",
                            name = userDoc.getString("name") ?: "",
                            roleString = userDoc.getString("role") ?: "user",
                            clientDocName = clientDoc.id,
                            clientName = clientName,
                            fcmToken = userDoc.getString("fcmToken")
                        )
                    } else null
                }

                users.addAll(clientUsers)
                Log.d(TAG, "Retrieved ${clientUsers.size} users for client ${clientDoc.id}")
            }

            Log.d(TAG, "Retrieved ${users.size} total users")
            Result.success(users)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting users: ${e.message}")
            Result.failure(e)
        }
    }

    private fun DocumentSnapshot.toUserData(
        defaultRole: UserRole,
        clientDocName: String,
        clientName: String
    ): UserData? {
        return try {
            UserData(
                documentName = id,
                email = getString("email") ?: "",
                name = getString("name") ?: "",
                roleString = getString("role") ?: UserRole.toFirestoreValue(defaultRole),
                clientDocName = clientDocName,
                clientName = clientName,
                fcmToken = getString("fcmToken"),
                phone = getString("phone") ?: "" // Agregado el campo phone
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error converting document to UserData: ${e.message}")
            null
        }
    }

    suspend fun createUser(user: UserData): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Primero crear la cuenta en Firebase Authentication
            val authResult = auth.createUserWithEmailAndPassword(user.email, user.password).await()
            Log.d(TAG, "Usuario creado en Firebase Auth: ${authResult.user?.uid}")

            val documentName = when (user.role) {
                UserRole.ADMIN -> IdManager.generateAdminDocumentName(user.name)
                UserRole.USER -> IdManager.generateUserDocumentName(user.name, user.clientDocName)
            }

            val collectionPath = when (user.role) {
                UserRole.ADMIN -> "$BASE_PATH/admins"
                UserRole.USER -> "$BASE_PATH/clients/${user.clientDocName}/users"
            }

            val userMap = mapOf(
                "documentName" to documentName,
                "email" to user.email,
                "name" to user.name,
                "role" to UserRole.toFirestoreValue(user.role),
                "clientDocName" to user.clientDocName,
                "fcmToken" to user.fcmToken,
                "phone" to user.phone,
                "password" to user.password // Guardar la contraseña en Firestore para referencia
            )

            firestore.collection(collectionPath)
                .document(documentName)
                .set(userMap)
                .await()

            Log.d(TAG, "Usuario creado exitosamente: $documentName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error creando usuario: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateUser(user: UserData): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (user.documentName.isEmpty()) {
                throw IllegalArgumentException("Invalid document name")
            }

            val collectionPath = when (user.role) {
                UserRole.ADMIN -> "$BASE_PATH/admins"
                UserRole.USER -> "$BASE_PATH/clients/${user.clientDocName}/users"
            }

            val userMap = mapOf(
                "documentName" to user.documentName,
                "email" to user.email,
                "name" to user.name,
                "role" to UserRole.toFirestoreValue(user.role),
                "clientDocName" to user.clientDocName,
                "fcmToken" to user.fcmToken,
                "phone" to user.phone // Agregado el campo phone
            )

            firestore.collection(collectionPath)
                .document(user.documentName)
                .set(userMap)
                .await()

            Log.d(TAG, "User updated successfully: ${user.documentName}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating user: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteUser(documentName: String, clientDocName: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (documentName.isEmpty()) {
                throw IllegalArgumentException("Invalid document name")
            }

            val collectionPath = if (documentName.startsWith(IdManager.PREFIX_ADMIN)) {
                "$BASE_PATH/admins"
            } else {
                requireNotNull(clientDocName) { "Client document name is required for client users" }
                "$BASE_PATH/clients/$clientDocName/users"
            }

            firestore.collection(collectionPath)
                .document(documentName)
                .delete()
                .await()

            Log.d(TAG, "User deleted successfully: $documentName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting user: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateFcmToken(userDocName: String, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (userDocName.isEmpty()) {
                throw IllegalArgumentException("Invalid document name")
            }

            val user = getCurrentUser() ?: throw Exception("No user logged in")
            val collectionPath = when (user.role) {
                UserRole.ADMIN -> "$BASE_PATH/admins"
                UserRole.USER -> "$BASE_PATH/clients/${user.clientDocName}/users"
            }

            firestore.collection(collectionPath)
                .document(userDocName)
                .update("fcmToken", token)
                .await()

            Log.d(TAG, "FCM token updated successfully: $userDocName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating FCM token: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getCurrentUser(): UserData? = withContext(Dispatchers.IO) {
        val firebaseUser = auth.currentUser
        if (firebaseUser == null) {
            Log.d(TAG, "No Firebase user found")
            return@withContext null
        }
        Log.d(TAG, "Firebase user: ${firebaseUser.uid}")

        try {
            val adminUser = findUserInCollection("$BASE_PATH/admins", firebaseUser.email, UserRole.ADMIN)
            if (adminUser != null) return@withContext adminUser

            val clientsQuery = firestore.collection("$BASE_PATH/clients").get().await()
            for (clientDoc in clientsQuery.documents) {
                val clientName = clientDoc.getString("name") ?: clientDoc.id
                val user = findUserInCollection(
                    "$BASE_PATH/clients/${clientDoc.id}/users",
                    firebaseUser.email,
                    UserRole.USER,
                    clientDoc.id,
                    clientName
                )
                if (user != null) return@withContext user
            }

            Log.e(TAG, "User not found in Firestore")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current user: ${e.message}")
            null
        }
    }

    suspend fun getAllUsers(): Result<List<UserData>> = withContext(Dispatchers.IO) {
        try {
            val allUsers = mutableListOf<UserData>()

            // Obtener usuarios administradores
            val adminsSnapshot = firestore.collection("$BASE_PATH/admins")
                .get()
                .await()

            for (adminDoc in adminsSnapshot.documents) {
                if (adminDoc.exists()) {
                    UserData(
                        documentName = adminDoc.id,
                        email = adminDoc.getString("email") ?: "",
                        name = adminDoc.getString("name") ?: "",
                        roleString = "admin",
                        clientDocName = "",
                        clientName = "",
                        fcmToken = adminDoc.getString("fcmToken"),
                        phone = adminDoc.getString("phone") ?: ""
                    ).also { allUsers.add(it) }
                }
            }

            // Obtener usuarios cliente
            val clientsQuery = firestore.collection("$BASE_PATH/clients").get().await()
            for (clientDoc in clientsQuery.documents) {
                val clientName = clientDoc.getString("name") ?: clientDoc.id
                val usersQuery = clientDoc.reference.collection("users").get().await()

                for (userDoc in usersQuery.documents) {
                    if (userDoc.exists()) {
                        UserData(
                            documentName = userDoc.id,
                            email = userDoc.getString("email") ?: "",
                            name = userDoc.getString("name") ?: "",
                            roleString = "user",
                            clientDocName = clientDoc.id,
                            clientName = clientName,
                            fcmToken = userDoc.getString("fcmToken"),
                            phone = userDoc.getString("phone") ?: ""
                        ).also { allUsers.add(it) }
                    }
                }
            }

            Log.d(TAG, "Retrieved total ${allUsers.size} users")
            Result.success(allUsers)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all users", e)
            Result.failure(e)
        }
    }

    private suspend fun findUserInCollection(
        collectionPath: String,
        email: String?,
        defaultRole: UserRole,
        clientDocName: String = "",
        clientName: String = ""
    ): UserData? {
        val query = firestore.collection(collectionPath)
            .whereEqualTo("email", email)
            .get()
            .await()

        if (!query.isEmpty) {
            val userDoc = query.documents.first()
            Log.d(TAG, "User found in collection: $collectionPath")
            return userDoc.toUserData(defaultRole, clientDocName, clientName)
        }
        return null
    }
}