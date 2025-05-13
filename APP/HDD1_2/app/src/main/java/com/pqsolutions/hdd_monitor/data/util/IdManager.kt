package com.pqsolutions.hdd_monitor.data.util

object IdManager {
    // Prefijos para tipos de documentos
    const val PREFIX_ADMIN = "admin"
    private const val PREFIX_USER = "user"
    private const val PREFIX_EVENT = "event"
    private const val PREFIX_NOTIFICATION = "notif"
    private const val PREFIX_PANEL = "panel"
    private const val PREFIX_CLIENT = "client"

    // Longitud estándar para IDs
    private const val ID_LENGTH = 8

    /**
     * Genera un ID único basado en un nombre y un prefijo
     */
    private fun generateUniqueId(name: String, prefix: String): String {
        val normalizedName = name
            .replace(Regex("[^A-Za-z0-9]"), "") // Elimina caracteres especiales
            .take(4) // Toma los primeros 4 caracteres
            .uppercase()

        val randomPart = generateRandomString(4) // 4 caracteres aleatorios
        return "${prefix}_${normalizedName}${randomPart}"
    }

    /**
     * Genera un nombre de documento para un cliente
     */
    fun generateClientDocumentName(clientName: String): String {
        return generateUniqueId(clientName, PREFIX_CLIENT)
    }

    /**
     * Genera un nombre de documento para un administrador
     */
    fun generateAdminDocumentName(adminName: String): String {
        return generateUniqueId(adminName, PREFIX_ADMIN)
    }

    /**
     * Genera un nombre de documento para un usuario dentro de un cliente
     */
    fun generateUserDocumentName(userName: String, clientDocName: String): String {
        val userPart = generateUniqueId(userName, PREFIX_USER)
        return "${userPart}_${clientDocName}"
    }

    /**
     * Genera un nombre de documento para un evento de un cliente
     */
    fun generateEventDocumentName(eventName: String, clientDocName: String): String {
        val eventPart = generateUniqueId(eventName, PREFIX_EVENT)
        return "${eventPart}_${clientDocName}"
    }

    /**
     * Genera un nombre de documento para una notificación de un cliente
     */
    fun generateNotificationDocumentName(notificationText: String, clientDocName: String): String {
        val notifPart = generateUniqueId(notificationText, PREFIX_NOTIFICATION)
        return "${notifPart}_${clientDocName}"
    }

    /**
     * Genera un nombre de documento para un panel de un cliente
     */
    fun generatePanelDocumentName(panelName: String, clientDocName: String): String {
        val panelPart = generateUniqueId(panelName, PREFIX_PANEL)
        return "${panelPart}_${clientDocName}"
    }

    /**
     * Valida el formato de un nombre de documento
     */
    fun validateDocumentName(documentName: String, type: DocumentType): Boolean {
        val basePattern = "[A-Z0-9]{8}"
        val pattern = when (type) {
            DocumentType.ADMIN -> """^${PREFIX_ADMIN}_$basePattern$"""
            DocumentType.CLIENT -> """^${PREFIX_CLIENT}_$basePattern$"""
            DocumentType.USER -> """^${PREFIX_USER}_${basePattern}_(${PREFIX_CLIENT}_${basePattern})$"""
            DocumentType.EVENT -> """^${PREFIX_EVENT}_${basePattern}_(${PREFIX_CLIENT}_${basePattern})$"""
            DocumentType.NOTIFICATION -> """^${PREFIX_NOTIFICATION}_${basePattern}_(${PREFIX_CLIENT}_${basePattern})$"""
            DocumentType.PANEL -> """^${PREFIX_PANEL}_${basePattern}_(${PREFIX_CLIENT}_${basePattern})$"""
        }

        return Regex(pattern).matches(documentName) || isLegacyDocumentName(documentName)
    }

    /**
     * Verifica si es un nombre de documento legacy
     */
    private fun isLegacyDocumentName(documentName: String): Boolean {
        val legacyPatterns = mapOf(
            "client" to """^client_\d+$""",
            "admin" to """^[A-Za-z0-9]{20}$""",
            "user" to """^[A-Za-z0-9]{20}$""",
            "event" to """^event_\d+$""",
            "notification" to """^notification_\d+$""",
            "panel" to """^panel_\d+$"""
        )

        return legacyPatterns.any { (_, pattern) ->
            Regex(pattern).matches(documentName)
        }
    }

    /**
     * Extrae el nombre del documento del cliente de un nombre de documento secundario
     */
    fun extractClientDocumentName(documentName: String): String? {
        val pattern = """^(?:${PREFIX_EVENT}|${PREFIX_NOTIFICATION}|${PREFIX_PANEL})_[A-Z0-9]{8}_(${PREFIX_CLIENT}_[A-Z0-9]{8})""".toRegex()
        return pattern.find(documentName)?.groupValues?.get(1)
    }

    /**
     * Genera una cadena aleatoria de longitud específica
     */
    private fun generateRandomString(length: Int): String {
        val allowedChars = ('A'..'Z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }
}

/**
 * Enumeración de tipos de documentos
 */
enum class DocumentType {
    ADMIN,
    USER,
    CLIENT,
    EVENT,
    NOTIFICATION,
    PANEL
}