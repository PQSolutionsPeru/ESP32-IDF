package com.pqsolutions.hdd_monitor.domain.model

import com.google.firebase.firestore.PropertyName

enum class UserRole {
    @PropertyName("admin")
    ADMIN,

    @PropertyName("user")
    USER;

    companion object {
        fun fromString(value: String?): UserRole {
            return when (value?.lowercase()) {
                "admin" -> ADMIN
                "user" -> USER
                null -> USER
                else -> try {
                    valueOf(value.uppercase())
                } catch (e: IllegalArgumentException) {
                    USER
                }
            }
        }

        fun toFirestoreValue(role: UserRole): String {
            return when (role) {
                ADMIN -> "admin"
                USER -> "user"
            }
        }
    }

    val isAdmin: Boolean
        get() = this == ADMIN

    val canManageEvents: Boolean
        get() = true // Permite a todos manejar eventos

    val canCreateEventTypes: Boolean
        get() = this == ADMIN // Solo admins pueden crear tipos

    val canViewAllClients: Boolean
        get() = this == ADMIN

    override fun toString(): String {
        return toFirestoreValue(this)  // Retorna "admin" o "user"
    }
}