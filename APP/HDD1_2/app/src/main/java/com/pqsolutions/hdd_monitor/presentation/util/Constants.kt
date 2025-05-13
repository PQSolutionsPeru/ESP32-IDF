package com.pqsolutions.hdd_monitor.util

import java.time.ZoneId

object Constants {
    // Configuración de WhatsApp
    const val ADMIN_WHATSAPP_NUMBER = "+51993533004"
    const val WHATSAPP_BASE_URL = "https://wa.me/"

    // Duración de animaciones
    const val ANIMATION_DURATION = 300 // milisegundos
    const val HAPTIC_DURATION = 50L // milisegundos

    // Prefijos de documentos
    object DocumentPrefixes {
        const val ADMIN = "admin_"
        const val CLIENT = "client_"
        const val EVENT = "event_"
        const val NOTIFICATION = "notification_"
        const val PANEL = "panel_"
        const val USER = "user_"
    }

    object TimeZone {
        val PERU_ZONE: ZoneId = ZoneId.of("America/Lima")
    }

    // Firebase Collections
    object FirebaseCollections {
        const val ROOT = "hdd-monitor"
        const val ACCOUNTS = "accounts"
        const val CLIENTS = "clients"
        const val EVENTS = "events"
        const val NOTIFICATIONS = "notifications"
        const val PANELS = "panels"
        const val USERS = "users"
    }

    // Rutas de Firestore
    object FirestorePaths {
        const val CLIENTS_PATH = "${FirebaseCollections.ROOT}/${FirebaseCollections.ACCOUNTS}/${FirebaseCollections.CLIENTS}"

        fun getClientEventsPath(clientDocName: String) =
            "$CLIENTS_PATH/$clientDocName/${FirebaseCollections.EVENTS}"

        fun getClientNotificationsPath(clientDocName: String) =
            "$CLIENTS_PATH/$clientDocName/${FirebaseCollections.NOTIFICATIONS}"

        fun getClientPanelsPath(clientDocName: String) =
            "$CLIENTS_PATH/$clientDocName/${FirebaseCollections.PANELS}"

        fun getPanelRelaysPath(clientDocName: String, panelDocName: String) =
            "${getClientPanelsPath(clientDocName)}/$panelDocName/relays"
    }

    // Tags para logging
    object LogTags {
        const val EVENT_VM = "EventViewModel"
        const val NOTIFICATION_VM = "NotificationViewModel"
        const val EVENT_REPO = "EventRepository"
        const val NOTIFICATION_REPO = "NotificationRepository"
    }

    // Valores por defecto
    object Defaults {
        const val DEFAULT_PAGE_SIZE = 20
        const val MIN_SEARCH_LENGTH = 3
        const val MAX_TITLE_LENGTH = 100
        const val MAX_DESCRIPTION_LENGTH = 500
        const val MAX_DOCUMENT_NAME_LENGTH = 100
    }

    // Patrones de formato
    object Patterns {
        const val DATE_TIME_PATTERN = "dd/MM/yyyy HH:mm"
        const val DATE_PATTERN = "dd/MM/yyyy"
        const val TIME_PATTERN = "HH:mm"
        const val DOCUMENT_NAME_PATTERN = "^[a-zA-Z0-9_-]+$"
    }

    // Tiempos de timeout
    object Timeouts {
        const val NETWORK_TIMEOUT = 30000L // 30 segundos
        const val CACHE_TIMEOUT = 3600000L // 1 hora
        const val TOKEN_REFRESH_INTERVAL = 24 * 60 * 60 * 1000L // 24 horas
    }

    // IDs de canales de notificación
    object NotificationChannels {
        const val EVENT_CHANNEL_ID = "event_channel"
        const val DEFAULT_CHANNEL_ID = "default_channel"
        const val EVENT_CHANNEL_NAME = "Eventos"
        const val DEFAULT_CHANNEL_NAME = "General"
    }

    // IDs de pantallas para navegación
    object Screens {
        const val EVENT_LIST = "event_list"
        const val EVENT_DETAIL = "event_detail"
        const val NOTIFICATION_HISTORY = "notification_history"
        const val USER_PROFILE = "user_profile"
    }

    // Códigos de error personalizados
    object ErrorCodes {
        const val NETWORK_ERROR = "ERROR_NETWORK"
        const val AUTH_ERROR = "ERROR_AUTH"
        const val INVALID_DATA = "ERROR_INVALID_DATA"
        const val NOT_FOUND = "ERROR_NOT_FOUND"
        const val INVALID_DOCUMENT_NAME = "ERROR_INVALID_DOCUMENT_NAME"
    }

    // Preferencias de usuario
    object PreferenceKeys {
        const val USER_PREFERENCES = "user_preferences"
        const val CLIENT_DOCUMENT_NAME = "client_document_name"
        const val USER_DOCUMENT_NAME = "user_document_name"
        const val LAST_SYNC_DATE = "last_sync_date"
        const val FCM_TOKEN = "fcm_token"
    }

    // Estados para paneles y relays
    object Status {
        const val OK = "OK"
        const val DISC = "DISC"
        const val STATUS_PROGRAMADO = "PROGRAMADO"
        const val STATUS_ACEPTADO = "ACEPTADO"
    }
}