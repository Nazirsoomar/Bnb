package com.example.data.preferences

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dualphone_settings")

enum class DeviceRole(val wireValue: String) {
    UNSET("unset"),
    PRIMARY_SIM("primary_sim"),
    REMOTE_VIEWER("remote_viewer");

    companion object {
        fun fromWire(value: String?): DeviceRole =
            entries.firstOrNull { it.wireValue == value } ?: UNSET
    }
}

data class UserSettings(
    val deviceId: String = "",
    val deviceName: String = defaultDeviceName(),
    val deviceRole: DeviceRole = DeviceRole.UNSET,
    val onboardingCompleted: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val twoWaySmsEnabled: Boolean = true,
    val notifyOnRemoteSms: Boolean = true,
    val notificationSound: Boolean = true,
    val notificationVibration: Boolean = true,
    val requireAuthLock: Boolean = false,
    val lastSyncTimestamp: Long = 0L
) {
    companion object {
        fun defaultDeviceName(): String {
            val brand = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
            val model = Build.MODEL ?: "Phone"
            return if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
        }
    }
}

class AppPreferences(private val context: Context) {

    private val store = context.applicationContext.dataStore

    val settingsFlow: Flow<UserSettings> = store.data.map { prefs ->
        UserSettings(
            deviceId = prefs[KEY_DEVICE_ID].orEmpty(),
            deviceName = prefs[KEY_DEVICE_NAME] ?: UserSettings.defaultDeviceName(),
            deviceRole = DeviceRole.fromWire(prefs[KEY_DEVICE_ROLE]),
            onboardingCompleted = prefs[KEY_ONBOARDING_DONE] ?: false,
            autoSyncEnabled = prefs[KEY_AUTO_SYNC] ?: true,
            twoWaySmsEnabled = prefs[KEY_TWO_WAY_SMS] ?: true,
            notifyOnRemoteSms = prefs[KEY_NOTIFY_REMOTE] ?: true,
            notificationSound = prefs[KEY_NOTIFY_SOUND] ?: true,
            notificationVibration = prefs[KEY_NOTIFY_VIBRATION] ?: true,
            requireAuthLock = prefs[KEY_AUTH_LOCK] ?: false,
            lastSyncTimestamp = prefs[KEY_LAST_SYNC] ?: 0L
        )
    }

    suspend fun getOrCreateDeviceId(): String {
        val current = store.data.first()[KEY_DEVICE_ID]
        if (!current.isNullOrBlank()) return current
        val generated = "dev_" + UUID.randomUUID().toString().replace("-", "").take(16)
        store.edit { it[KEY_DEVICE_ID] = generated }
        return generated
    }

    suspend fun setDeviceRole(role: DeviceRole, markOnboardingDone: Boolean = true) {
        getOrCreateDeviceId()
        store.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = role.wireValue
            if (markOnboardingDone) prefs[KEY_ONBOARDING_DONE] = true
        }
    }

    suspend fun setDeviceName(name: String) {
        val trimmed = name.trim().take(60)
        if (trimmed.isNotEmpty()) {
            store.edit { it[KEY_DEVICE_NAME] = trimmed }
        }
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        store.edit { it[KEY_AUTO_SYNC] = enabled }
    }

    suspend fun setTwoWaySmsEnabled(enabled: Boolean) {
        store.edit { it[KEY_TWO_WAY_SMS] = enabled }
    }

    suspend fun setNotifyOnRemoteSms(enabled: Boolean) {
        store.edit { it[KEY_NOTIFY_REMOTE] = enabled }
    }

    suspend fun setNotificationSound(enabled: Boolean) {
        store.edit { it[KEY_NOTIFY_SOUND] = enabled }
    }

    suspend fun setNotificationVibration(enabled: Boolean) {
        store.edit { it[KEY_NOTIFY_VIBRATION] = enabled }
    }

    suspend fun setRequireAuthLock(enabled: Boolean) {
        store.edit { it[KEY_AUTH_LOCK] = enabled }
    }

    suspend fun updateLastSyncTimestamp(timestamp: Long) {
        store.edit { it[KEY_LAST_SYNC] = timestamp }
    }

    suspend fun resetAll() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        val KEY_DEVICE_ROLE = stringPreferencesKey("device_role")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val KEY_TWO_WAY_SMS = booleanPreferencesKey("two_way_sms")
        val KEY_NOTIFY_REMOTE = booleanPreferencesKey("notify_remote")
        val KEY_NOTIFY_SOUND = booleanPreferencesKey("notify_sound")
        val KEY_NOTIFY_VIBRATION = booleanPreferencesKey("notify_vibration")
        val KEY_AUTH_LOCK = booleanPreferencesKey("auth_lock")
        val KEY_LAST_SYNC = longPreferencesKey("last_sync_ts")
    }
}
