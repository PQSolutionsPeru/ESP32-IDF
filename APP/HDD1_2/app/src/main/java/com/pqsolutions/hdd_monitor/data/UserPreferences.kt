package com.pqsolutions.hdd_monitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

@Singleton
class UserPreferences @Inject constructor(private val context: Context) {

    // Keys
    private val isFirstLaunchKey = booleanPreferencesKey("is_first_launch")
    private val clientDocNameKey = stringPreferencesKey("client_document_name")
    private val themeKey = stringPreferencesKey("theme")
    private val languageKey = stringPreferencesKey("language")
    private val notificationsEnabledKey = booleanPreferencesKey("notifications_enabled")
    private val authTokenKey = stringPreferencesKey("auth_token")
    private val userDocNameKey = stringPreferencesKey("user_document_name")
    private val userNameKey = stringPreferencesKey("user_name")
    private val userEmailKey = stringPreferencesKey("user_email")
    private val userRoleStringKey = stringPreferencesKey("user_role_string")
    private val lastSyncDateKey = longPreferencesKey("last_sync_date")
    private val sessionValidKey = booleanPreferencesKey("session_valid")
    private val sessionTokenKey = stringPreferencesKey("session_token")

    // Flow getters
    val isFirstLaunchFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[isFirstLaunchKey] ?: true
    }

    val clientDocNameFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[clientDocNameKey]
    }

    val themeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[themeKey] ?: "system"
    }

    val languageFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[languageKey] ?: "es"
    }

    val notificationsEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[notificationsEnabledKey] ?: true
    }

    val authTokenFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[authTokenKey]
    }

    val userDataFlow: Flow<UserData?> = context.dataStore.data.map { preferences ->
        val userDocName = preferences[userDocNameKey]
        val userName = preferences[userNameKey]
        val userEmail = preferences[userEmailKey]
        val roleString = preferences[userRoleStringKey]
        val clientDocName = preferences[clientDocNameKey]
        val isSessionValid = preferences[sessionValidKey] ?: false

        if (userDocName != null && userName != null && userEmail != null && roleString != null && isSessionValid) {
            UserData(
                documentName = userDocName,
                name = userName,
                email = userEmail,
                roleString = roleString,
                clientDocName = clientDocName ?: ""
            )
        } else {
            null
        }
    }

    val lastSyncDateFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[lastSyncDateKey] ?: 0L
    }

    val sessionTokenFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[sessionTokenKey]
    }

    // Setters
    suspend fun setFirstLaunch(isFirstLaunch: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[isFirstLaunchKey] = isFirstLaunch
        }
    }

    suspend fun setClientDocName(clientDocName: String) {
        context.dataStore.edit { preferences ->
            preferences[clientDocNameKey] = clientDocName
        }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[themeKey] = theme
        }
    }

    suspend fun setLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[languageKey] = language
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[notificationsEnabledKey] = enabled
        }
    }

    suspend fun setAuthToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[authTokenKey] = token
        }
    }

    suspend fun setUserData(userData: UserData) {
        context.dataStore.edit { preferences ->
            preferences[userDocNameKey] = userData.documentName
            preferences[userNameKey] = userData.name
            preferences[userEmailKey] = userData.email
            preferences[userRoleStringKey] = UserRole.toFirestoreValue(userData.role)
            preferences[clientDocNameKey] = userData.clientDocName
            preferences[sessionValidKey] = true
        }
    }

    suspend fun setSessionToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[sessionTokenKey] = token
        }
    }

    suspend fun getSessionToken(): String? {
        return context.dataStore.data.first()[sessionTokenKey]
    }

    suspend fun setLastSyncDate(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[lastSyncDateKey] = timestamp
        }
    }

    suspend fun getUserData(): UserData? {
        return userDataFlow.first()
    }

    suspend fun isSessionValid(): Boolean {
        return context.dataStore.data.first()[sessionValidKey] ?: false
    }

    suspend fun clearUserData() {
        context.dataStore.edit { preferences ->
            preferences[userDocNameKey] = ""
            preferences[userNameKey] = ""
            preferences[userEmailKey] = ""
            preferences[userRoleStringKey] = ""
            preferences[clientDocNameKey] = ""
            preferences[authTokenKey] = ""
            preferences[sessionValidKey] = false
            preferences[sessionTokenKey] = ""
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}