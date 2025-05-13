package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.pqsolutions.hdd_monitor.util.Constants
import com.pqsolutions.hdd_monitor.util.Constants.DocumentPrefixes
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClientRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val clientsCollection = firestore
        .collection(Constants.FirebaseCollections.ROOT)
        .document(Constants.FirebaseCollections.ACCOUNTS)
        .collection(Constants.FirebaseCollections.CLIENTS)

    suspend fun getClients(): Result<List<Client>> = try {
        Log.d(TAG, "Getting clients")
        val snapshot = clientsCollection
            .orderBy("name", Query.Direction.ASCENDING)
            .get()
            .await()

        val clients = snapshot.documents.mapNotNull { doc ->
            doc.toObject(Client::class.java)?.copy(
                documentName = doc.id
            )
        }
        Log.d(TAG, "Retrieved ${clients.size} clients")
        Result.success(clients)
    } catch (e: Exception) {
        Log.e(TAG, "Error getting clients", e)
        Result.failure(e)
    }

    suspend fun getClient(clientId: String): Result<Client> = try {
        Log.d(TAG, "Getting client: $clientId")
        val doc = clientsCollection.document(clientId).get().await()
        val client = doc.toObject(Client::class.java)?.copy(
            documentName = doc.id
        ) ?: throw Exception("Client not found")
        Log.d(TAG, "Retrieved client: ${client.name}")
        Result.success(client)
    } catch (e: Exception) {
        Log.e(TAG, "Error getting client: $clientId", e)
        Result.failure(e)
    }

    suspend fun createClient(name: String): Result<Client> = try {
        Log.d(TAG, "Creating client: $name")
        val clientId = "${DocumentPrefixes.CLIENT}${UUID.randomUUID()}"
        val client = Client(documentName = clientId, name = name)

        clientsCollection.document(clientId)
            .set(client)
            .await()

        Log.d(TAG, "Created client: $clientId")
        Result.success(client)
    } catch (e: Exception) {
        Log.e(TAG, "Error creating client", e)
        Result.failure(e)
    }

    suspend fun updateClient(client: Client): Result<Unit> = try {
        Log.d(TAG, "Updating client: ${client.documentName}")
        clientsCollection.document(client.documentName)
            .set(client)
            .await()

        Log.d(TAG, "Updated client: ${client.documentName}")
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "Error updating client: ${client.documentName}", e)
        Result.failure(e)
    }

    suspend fun deleteClient(clientId: String): Result<Unit> = try {
        Log.d(TAG, "Deleting client: $clientId")
        clientsCollection.document(clientId)
            .delete()
            .await()

        Log.d(TAG, "Deleted client: $clientId")
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "Error deleting client: $clientId", e)
        Result.failure(e)
    }

    companion object {
        private const val TAG = "ClientRepository"
    }
}