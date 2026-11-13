package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.ucnDataStore: DataStore<Preferences> by preferencesDataStore(name = "ucn_preferences")

data class LocalAppPreferences(
    val darkMode: String = "dark", // "system" | "dark" | "light"
    val readReceiptsEnabled: Boolean = true,
    val voipCodecProfile: String = "opus_48k_vp9_hd",
    val autoSyncEnabled: Boolean = true,
    val offlineSimulationMode: Boolean = false,
    val highContrastAccessibility: Boolean = false,
    val contactAutoSyncEnabled: Boolean = false,
    val lastContactSyncMs: Long = 0L,
    val lastScannedContactsCount: Int = 0
)

class UserPreferencesDataStore(private val context: Context) {
    private val appContext = context.applicationContext

    private object Keys {
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val READ_RECEIPTS = booleanPreferencesKey("read_receipts_enabled")
        val VOIP_CODEC = stringPreferencesKey("voip_codec_profile")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
        val OFFLINE_SIM = booleanPreferencesKey("offline_simulation_mode")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast_accessibility")
        val DEVICE_ID = stringPreferencesKey("ucn_device_id")
        val CONTACT_AUTO_SYNC = booleanPreferencesKey("contact_auto_sync_enabled")
        val LAST_CONTACT_SYNC_MS = androidx.datastore.preferences.core.longPreferencesKey("last_contact_sync_ms")
        val LAST_SCANNED_CONTACTS = androidx.datastore.preferences.core.intPreferencesKey("last_scanned_contacts")
    }

    val preferencesFlow: Flow<LocalAppPreferences> = appContext.ucnDataStore.data.map { prefs ->
        LocalAppPreferences(
            darkMode = prefs[Keys.DARK_MODE] ?: "dark",
            readReceiptsEnabled = prefs[Keys.READ_RECEIPTS] ?: true,
            voipCodecProfile = prefs[Keys.VOIP_CODEC] ?: "opus_48k_vp9_hd",
            autoSyncEnabled = prefs[Keys.AUTO_SYNC] ?: true,
            offlineSimulationMode = prefs[Keys.OFFLINE_SIM] ?: false,
            highContrastAccessibility = prefs[Keys.HIGH_CONTRAST] ?: false,
            contactAutoSyncEnabled = prefs[Keys.CONTACT_AUTO_SYNC] ?: false,
            lastContactSyncMs = prefs[Keys.LAST_CONTACT_SYNC_MS] ?: 0L,
            lastScannedContactsCount = prefs[Keys.LAST_SCANNED_CONTACTS] ?: 0
        )
    }

    suspend fun getOrCreateDeviceId(): String {
        val current = appContext.ucnDataStore.data.first()[Keys.DEVICE_ID]
        if (!current.isNullOrBlank()) return current
        val created = "dev_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        appContext.ucnDataStore.edit { it[Keys.DEVICE_ID] = created }
        return created
    }

    suspend fun setDarkMode(mode: String) {
        appContext.ucnDataStore.edit { it[Keys.DARK_MODE] = mode }
    }

    suspend fun setReadReceiptsEnabled(enabled: Boolean) {
        appContext.ucnDataStore.edit { it[Keys.READ_RECEIPTS] = enabled }
    }

    suspend fun setVoipCodecProfile(profile: String) {
        appContext.ucnDataStore.edit { it[Keys.VOIP_CODEC] = profile }
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        appContext.ucnDataStore.edit { it[Keys.AUTO_SYNC] = enabled }
    }

    suspend fun setOfflineSimulationMode(enabled: Boolean) {
        appContext.ucnDataStore.edit { it[Keys.OFFLINE_SIM] = enabled }
    }

    suspend fun setHighContrastAccessibility(enabled: Boolean) {
        appContext.ucnDataStore.edit { it[Keys.HIGH_CONTRAST] = enabled }
    }

    suspend fun setContactAutoSyncEnabled(enabled: Boolean) {
        appContext.ucnDataStore.edit { it[Keys.CONTACT_AUTO_SYNC] = enabled }
    }

    suspend fun recordContactSyncStats(timestampMs: Long, scannedCount: Int) {
        appContext.ucnDataStore.edit {
            it[Keys.LAST_CONTACT_SYNC_MS] = timestampMs
            it[Keys.LAST_SCANNED_CONTACTS] = scannedCount
        }
    }

    suspend fun applySyncedProfile(
        darkMode: String,
        readReceiptsEnabled: Boolean,
        voipCodecProfile: String,
        autoSyncEnabled: Boolean
    ) {
        appContext.ucnDataStore.edit {
            it[Keys.DARK_MODE] = darkMode
            it[Keys.READ_RECEIPTS] = readReceiptsEnabled
            it[Keys.VOIP_CODEC] = voipCodecProfile
            it[Keys.AUTO_SYNC] = autoSyncEnabled
        }
    }
}
