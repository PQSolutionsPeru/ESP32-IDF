package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.pqsolutions.hdd_monitor.data.util.IdManager
import com.pqsolutions.hdd_monitor.domain.model.EventStatus
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    companion object {
        private const val TAG = "EventRepository"
        private const val BASE_PATH = "hdd-monitor/accounts/clients"
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
    }

    private var eventListeners = mutableListOf<ListenerRegistration>()

    /**
     * Método dummy para reemplazar la funcionalidad de programación de notificaciones
     * que ha sido eliminada. Se mantuvo para compatibilidad con HddApplication.
     */
    suspend fun scheduleAllPendingEventNotifications() {
        Log.d(TAG, "La funcionalidad de programación de notificaciones para eventos ha sido desactivada")
    }

    fun getAllEventsFlow(): Flow<List<Event>> = callbackFlow {
        if (auth.currentUser == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listenerRegistration = firestore.collectionGroup("events")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error is FirebaseFirestoreException &&
                        error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        trySend(emptyList())
                        close()
                        return@addSnapshotListener
                    }
                    Log.e(TAG, "Error getting events", error)
                    close(error)
                    return@addSnapshotListener
                }

                if (auth.currentUser == null) {
                    trySend(emptyList())
                    close()
                    return@addSnapshotListener
                }

                snapshot?.let {
                    val events = it.documents.mapNotNull { doc ->
                        try {
                            val clientDocName = doc.reference.path
                                .split("/")
                                .find { segment -> segment.startsWith("client_") }

                            Event.fromMap(doc.data?.plus(mapOf(
                                "documentName" to doc.id,
                                "clientDocName" to (clientDocName ?: "")
                            )) ?: emptyMap())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting document ${doc.id}: ${e.message}", e)
                            null
                        }
                    }
                    trySend(events)
                }
            }

        eventListeners.add(listenerRegistration)
        awaitClose {
            listenerRegistration.remove()
            eventListeners.remove(listenerRegistration)
        }
    }

    fun getEventsFlow(clientDocName: String): Flow<List<Event>> = callbackFlow {
        if (clientDocName.isEmpty() || auth.currentUser == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listenerRegistration = firestore
            .collection("$BASE_PATH/$clientDocName/events")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error is FirebaseFirestoreException &&
                        error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        trySend(emptyList())
                        close()
                        return@addSnapshotListener
                    }
                    Log.e(TAG, "Error getting client events", error)
                    close(error)
                    return@addSnapshotListener
                }

                if (auth.currentUser == null) {
                    trySend(emptyList())
                    close()
                    return@addSnapshotListener
                }

                snapshot?.let {
                    val events = it.documents.mapNotNull { doc ->
                        try {
                            Event.fromMap(doc.data?.plus(mapOf(
                                "documentName" to doc.id,
                                "clientDocName" to clientDocName
                            )) ?: emptyMap())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting document ${doc.id}: ${e.message}", e)
                            null
                        }
                    }
                    trySend(events)
                }
            }

        eventListeners.add(listenerRegistration)
        awaitClose {
            listenerRegistration.remove()
            eventListeners.remove(listenerRegistration)
        }
    }

    suspend fun createEvent(clientDocNames: List<String>, event: Event): Result<Unit> = runCatching {
        if (clientDocNames.isEmpty()) {
            throw IllegalArgumentException("Client document names list cannot be empty")
        }

        val batch = firestore.batch()
        // Usamos la zona horaria de Perú para la fecha/hora actual
        val peruZoneId = ZoneId.of("America/Lima")
        val now = LocalDateTime.now(peruZoneId).format(DATE_FORMATTER)

        for (clientDocName in clientDocNames) {
            val eventDocName = IdManager.generateEventDocumentName("Evento", clientDocName)
            Log.d(TAG, "Creating new event with document name: $eventDocName for client: $clientDocName")

            // Siempre asegurarnos de tener el nombre del panel
            var panelName = event.panelName
            event.panelDocName?.let { pDocName ->
                // Si ya hay un nombre de panel, no necesitamos buscarlo de nuevo
                if (panelName.isNullOrEmpty()) {
                    try {
                        val panelDoc = firestore.document("$BASE_PATH/$clientDocName/panels/$pDocName")
                            .get()
                            .await()
                        panelName = panelDoc.getString("name") ?: ""
                    } catch (e: Exception) {
                        Log.e(TAG, "Error al obtener nombre del panel: $pDocName", e)
                    }
                }
            }

            val eventRef = firestore
                .collection("$BASE_PATH/$clientDocName/events")
                .document(eventDocName)

            val eventData = hashMapOf<String, Any?>(
                "title" to event.title,
                "text" to event.text,
                "status" to EventStatus.STATUS_PROGRAMADO,
                "date_time" to event.date_time,
                "lastUpdate" to now,
                "panelDocName" to event.panelDocName,
                "panelName" to panelName,  // Aseguramos que siempre se guarde el nombre
                "type" to event.type,
                "createdByAccountId" to event.createdByAccountId,
                "createdByAccountRole" to event.createdByAccountRole,
                "needsApproval" to event.needsApproval,
                "isRead" to false
            ).apply {
                values.removeAll { it == null }
            }

            batch.set(eventRef, eventData)
        }

        // Fix: Evitamos la doble llamada a commit que está causando el error
        batch.commit().await()
        Log.d(TAG, "Events batch committed successfully")
    }

    suspend fun updateEventStatus(
        clientDocName: String,
        eventDocName: String,
        newStatus: String,
        updatedByAccountId: String,
        isAdmin: Boolean
    ): Result<Unit> = runCatching {
        if (clientDocName.isEmpty() || eventDocName.isEmpty()) {
            throw IllegalArgumentException("Client and Event document names cannot be empty")
        }

        val now = LocalDateTime.now().format(DATE_FORMATTER)
        val eventDoc = firestore.document("$BASE_PATH/$clientDocName/events/$eventDocName")
        val snapshot = eventDoc.get().await()

        if (!snapshot.exists()) {
            throw IllegalStateException("El evento no existe: $eventDocName")
        }

        val currentEvent = Event.fromMap(snapshot.data?.plus("documentName" to eventDocName) ?: emptyMap())

        // Validar que el usuario tenga permiso para actualizar el estado
        when {
            isAdmin && currentEvent.createdByAccountRole == UserRole.USER.toString() && currentEvent.needAdminAcceptance -> {
                // Admin aceptando evento de usuario
            }
            !isAdmin && currentEvent.createdByAccountRole == UserRole.ADMIN.toString() && currentEvent.needUserAcceptance -> {
                // Usuario aceptando evento de admin
            }
            newStatus == EventStatus.STATUS_FINALIZADO && currentEvent.isAceptado -> {
                // Permitir finalización si el evento está aceptado
            }
            else -> {
                throw IllegalStateException("No tiene permisos para actualizar este evento o el evento no está en un estado válido")
            }
        }

        if (!EventStatus.isValidTransition(currentEvent.status, newStatus)) {
            throw IllegalStateException("Transición de estado inválida: ${currentEvent.status} -> $newStatus")
        }

        val updates = mutableMapOf<String, Any>(
            "status" to newStatus,
            "lastUpdate" to now,
            "isRead" to false
        )

        when (newStatus) {
            EventStatus.STATUS_ACEPTADO -> {
                updates["acceptedAt"] = now
                updates["acceptedByAccountId"] = updatedByAccountId
            }
            EventStatus.STATUS_FINALIZADO -> {
                updates["finishedAt"] = now
                updates["finishedByAccountId"] = updatedByAccountId
            }
        }

        eventDoc.update(updates).await()
        Log.d(TAG, "Event status updated successfully: $eventDocName to $newStatus")
    }

    suspend fun updateEvent(clientDocName: String, event: Event, updateLastUpdate: Boolean = true): Result<Unit> = runCatching {
        if (clientDocName.isEmpty() || event.documentName.isEmpty()) {
            throw IllegalArgumentException("Client and Event document names cannot be empty")
        }

        val eventsCollection = firestore.collection("$BASE_PATH/$clientDocName/events")
        Log.d(TAG, "Attempting to update event: ${event.documentName}")

        val eventDoc = eventsCollection.document(event.documentName)
        val snapshot = eventDoc.get().await()

        if (!snapshot.exists()) {
            throw IllegalStateException("El evento no existe: ${event.documentName}")
        }

        // Siempre obtener nombre actualizado del panel
        var panelName: String? = event.panelName
        event.panelDocName?.let { pDocName ->
            // Solo buscar el nombre si no lo tenemos ya
            if (panelName.isNullOrEmpty()) {
                try {
                    val panelDoc = firestore
                        .document("$BASE_PATH/$clientDocName/panels/$pDocName")
                        .get()
                        .await()
                    panelName = panelDoc.getString("name")
                } catch (e: Exception) {
                    Log.e(TAG, "Error al obtener nombre del panel en updateEvent", e)
                }
            }
        }

        val eventData = event.toMap().toMutableMap()
        eventData["panelName"] = panelName

        // Solo actualizar lastUpdate si se solicita explícitamente
        if (updateLastUpdate) {
            // Actualizar explícitamente lastUpdate con la fecha y hora actual
            val now = LocalDateTime.now().format(DATE_FORMATTER)
            Log.d(TAG, "Actualizando lastUpdate a: $now para evento: ${event.documentName}")
            eventData["lastUpdate"] = now
        } else {
            // No actualizamos lastUpdate y utilizamos el valor existente
            Log.d(TAG, "Manteniendo lastUpdate original para evento: ${event.documentName}")
            // Quitamos lastUpdate del mapa para evitar actualizarlo
            eventData.remove("lastUpdate")
        }

        eventDoc.update(eventData).await()

        if (updateLastUpdate) {
            Log.d(TAG, "Event updated successfully with lastUpdate: ${event.documentName}")
        } else {
            Log.d(TAG, "Event updated successfully without changing lastUpdate: ${event.documentName}")
        }
    }

    suspend fun markEventAsRead(clientDocName: String, eventDocName: String): Result<Unit> = runCatching {
        firestore.document("$BASE_PATH/$clientDocName/events/$eventDocName")
            .update("isRead", true)
            .await()
    }

    suspend fun deleteEvent(clientDocName: String, eventDocName: String, isAdmin: Boolean = false): Result<Unit> = runCatching {
        if (clientDocName.isEmpty() || eventDocName.isEmpty()) {
            throw IllegalArgumentException("Client and Event document names cannot be empty")
        }

        val eventDoc = firestore.document("$BASE_PATH/$clientDocName/events/$eventDocName")
        val snapshot = eventDoc.get().await()

        if (!snapshot.exists()) {
            throw IllegalStateException("El evento no existe: $eventDocName")
        }

        val currentEvent = Event.fromMap(snapshot.data?.plus("documentName" to eventDocName) ?: emptyMap())

        // Si es admin, puede eliminar en cualquier estado
        // Si no es admin, solo puede eliminar eventos programados
        if (!isAdmin && !currentEvent.isProgramado) {
            throw IllegalStateException("Solo se pueden eliminar eventos en estado PROGRAMADO")
        }

        eventDoc.delete().await()
        Log.d(TAG, "Event deleted successfully: $eventDocName")

    }


    suspend fun getClients(): List<Client> {
        return firestore.collection(BASE_PATH)
            .get()
            .await()
            .documents
            .mapNotNull { doc ->
                doc.toObject(Client::class.java)?.copy(documentName = doc.id)
            }
    }

    suspend fun getPanelsByClient(clientDocName: String): List<Panel> {
        return firestore.collection("$BASE_PATH/$clientDocName/panels")
            .get()
            .await()
            .documents
            .mapNotNull { doc ->
                doc.toObject(Panel::class.java)?.copy(documentName = doc.id)
            }
    }

    suspend fun updateEventWithoutLastUpdate(clientDocName: String, event: Event): Result<Unit> = runCatching {
        if (clientDocName.isEmpty() || event.documentName.isEmpty()) {
            throw IllegalArgumentException("Client and Event document names cannot be empty")
        }

        val eventsCollection = firestore.collection("$BASE_PATH/$clientDocName/events")
        Log.d(TAG, "Attempting to update event (without lastUpdate): ${event.documentName}")

        val eventDoc = eventsCollection.document(event.documentName)
        val snapshot = eventDoc.get().await()

        if (!snapshot.exists()) {
            throw IllegalStateException("El evento no existe: ${event.documentName}")
        }

        // Siempre obtener nombre actualizado del panel
        var panelName: String? = event.panelName
        event.panelDocName?.let { pDocName ->
            // Solo buscar el nombre si no lo tenemos ya
            if (panelName.isNullOrEmpty()) {
                try {
                    val panelDoc = firestore
                        .document("$BASE_PATH/$clientDocName/panels/$pDocName")
                        .get()
                        .await()
                    panelName = panelDoc.getString("name")
                } catch (e: Exception) {
                    Log.e(TAG, "Error al obtener nombre del panel en updateEvent", e)
                }
            }
        }

        val eventData = event.toMap().toMutableMap()
        eventData["panelName"] = panelName
        // Importante: NO actualizamos lastUpdate aquí

        eventDoc.update(eventData).await()
        Log.d(TAG, "Event updated successfully without lastUpdate change: ${event.documentName}")
    }

    fun clearListeners() {
        eventListeners.forEach { it.remove() }
        eventListeners.clear()
    }

    fun getListenerStats(): Map<String, Any> {
        return mapOf(
            "activeListeners" to eventListeners.size,
            "maxListeners" to 10,
            "timestamp" to System.currentTimeMillis()
        )
    }
}