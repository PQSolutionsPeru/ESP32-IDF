package com.pqsolutions.hdd_monitor.data.util

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartReminderManager @Inject constructor(
    private val context: Context
) {
    private val prefs = context.getSharedPreferences("battery_reminders", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_REMINDER_COUNT = "reminder_count"
        private const val KEY_LAST_REMINDER = "last_reminder"
        private const val KEY_USER_DISMISSED = "user_dismissed"

        private val REMINDER_INTERVALS = longArrayOf(
            4 * 60 * 60 * 1000L,
            12 * 60 * 60 * 1000L,
            24 * 60 * 60 * 1000L,
            3 * 24 * 60 * 60 * 1000L,
            7 * 24 * 60 * 60 * 1000L
        )
    }

    fun shouldShowReminder(): Boolean {
        val reminderCount = prefs.getInt(KEY_REMINDER_COUNT, 0)
        val lastReminder = prefs.getLong(KEY_LAST_REMINDER, 0)
        val userDismissed = prefs.getBoolean(KEY_USER_DISMISSED, false)

        if (userDismissed) return false
        if (reminderCount >= REMINDER_INTERVALS.size) return false

        val currentTime = System.currentTimeMillis()
        val interval = REMINDER_INTERVALS.getOrElse(reminderCount) {
            REMINDER_INTERVALS.last()
        }

        return currentTime - lastReminder >= interval
    }

    fun recordReminderShown() {
        val reminderCount = prefs.getInt(KEY_REMINDER_COUNT, 0)
        prefs.edit()
            .putInt(KEY_REMINDER_COUNT, reminderCount + 1)
            .putLong(KEY_LAST_REMINDER, System.currentTimeMillis())
            .apply()
    }

    fun userDismissedPermanently() {
        prefs.edit()
            .putBoolean(KEY_USER_DISMISSED, true)
            .apply()
    }

    fun resetReminders() {
        prefs.edit()
            .clear()
            .apply()
    }

    fun getReminderCount(): Int {
        return prefs.getInt(KEY_REMINDER_COUNT, 0)
    }

    fun getReminderMessage(reminderCount: Int): String {
        return when (reminderCount) {
            0 -> "Para recibir alertas de incendio 24/7, active los permisos de batería."
            1 -> "Las notificaciones de emergencia pueden no llegar sin configurar la batería."
            2 -> "Sistema de seguridad: Configure la batería para monitoreo continuo."
            3 -> "Última oportunidad: Active permisos para alertas críticas."
            else -> "Configure los permisos para un monitoreo confiable."
        }
    }

    fun isLastReminder(): Boolean {
        return getReminderCount() >= 3
    }
}