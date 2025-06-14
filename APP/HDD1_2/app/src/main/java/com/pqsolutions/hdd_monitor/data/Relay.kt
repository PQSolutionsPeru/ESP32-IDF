package com.pqsolutions.hdd_monitor.data

import java.io.Serializable

data class Relay(
    val name: String,
    val status: String,
    val date_time: String? = null,
    val customName: String? = null,
    val isActive: Boolean = true,
    val contactType: String = "NO",
    val lastCommandSent: Long? = null,
    val commandSource: String? = null
) : Serializable {

    val displayName: String
        get() = customName?.takeIf { it.isNotBlank() } ?: name

    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name,
        "status" to status,
        "date_time" to date_time,
        "customName" to customName,
        "isActive" to isActive,
        "contactType" to contactType,
        "lastCommandSent" to lastCommandSent,
        "commandSource" to commandSource
    )

    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_DISC = "DISC"
        const val CONTACT_TYPE_NO = "NO"
        const val CONTACT_TYPE_NC = "NC"

        fun fromMap(map: Map<String, Any?>): Relay = Relay(
            name = map["name"]?.toString() ?: "",
            status = map["status"]?.toString() ?: STATUS_DISC,
            date_time = map["date_time"]?.toString(),
            customName = map["customName"]?.toString(),
            isActive = map["isActive"] as? Boolean ?: true,
            contactType = map["contactType"]?.toString() ?: CONTACT_TYPE_NO,
            lastCommandSent = map["lastCommandSent"] as? Long,
            commandSource = map["commandSource"]?.toString()
        )
    }
}