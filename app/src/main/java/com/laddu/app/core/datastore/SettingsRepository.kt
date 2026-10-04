package com.laddu.app.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.laddu.app.core.model.AppMode
import com.laddu.app.core.model.CameraSettings
import com.laddu.app.core.model.NetworkSettings
import com.laddu.app.core.model.NotificationPrefs
import com.laddu.app.core.model.RecordingSettings
import com.laddu.app.core.model.enumOr
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** Single source of truth for on-device preferences (DataStore). */
@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    private object K {
        val mode = stringPreferencesKey("app_mode")
        val onboarded = booleanPreferencesKey("onboarded")
        val theme = stringPreferencesKey("theme")
        val cameraId = stringPreferencesKey("camera_id")
        val monitoringDesired = booleanPreferencesKey("monitoring_desired")
        val monitoringStartedAt = longPreferencesKey("monitoring_started_at")
        val selectedCameraId = stringPreferencesKey("selected_camera_id")
        val camera = stringPreferencesKey("camera_settings")
        val notif = stringPreferencesKey("notification_prefs")
        val recording = stringPreferencesKey("recording_settings")
        val network = stringPreferencesKey("network_settings")
        val oemGuideSeen = booleanPreferencesKey("oem_guide_seen")
        val localOnly = booleanPreferencesKey("local_only")
    }

    private fun <T> json(key: Preferences.Key<String>, parse: (JSONObject?) -> T): Flow<T> =
        store.data.map { p -> parse(p[key]?.let { runCatching { JSONObject(it) }.getOrNull() }) }

    val appMode: Flow<AppMode?> = store.data.map { p -> p[K.mode]?.let { m -> AppMode.entries.firstOrNull { it.name == m } } }
    val onboarded: Flow<Boolean> = store.data.map { it[K.onboarded] ?: false }
    val theme: Flow<ThemeChoice> = store.data.map { enumOr(it[K.theme], ThemeChoice.SYSTEM) }
    val monitoringDesired: Flow<Boolean> = store.data.map { it[K.monitoringDesired] ?: false }
    val selectedCameraId: Flow<String?> = store.data.map { it[K.selectedCameraId] }
    val oemGuideSeen: Flow<Boolean> = store.data.map { it[K.oemGuideSeen] ?: false }
    /** Camera mode without Firebase: monitoring + local events only. */
    val localOnly: Flow<Boolean> = store.data.map { it[K.localOnly] ?: false }

    val cameraSettings: Flow<CameraSettings> = json(K.camera, CameraSettings::fromJson)
    val notificationPrefs: Flow<NotificationPrefs> = json(K.notif, NotificationPrefs::fromJson)
    val recordingSettings: Flow<RecordingSettings> = json(K.recording, RecordingSettings::fromJson)
    val networkSettings: Flow<NetworkSettings> = json(K.network, NetworkSettings::fromJson)

    suspend fun setAppMode(mode: AppMode) = store.edit { it[K.mode] = mode.name; it[K.onboarded] = true }
    suspend fun clearAppMode() = store.edit { it.remove(K.mode) }
    suspend fun setTheme(choice: ThemeChoice) = store.edit { it[K.theme] = choice.name }
    suspend fun setSelectedCamera(id: String?) = store.edit { if (id == null) it.remove(K.selectedCameraId) else it[K.selectedCameraId] = id }
    suspend fun setLocalOnly(on: Boolean) = store.edit { it[K.localOnly] = on }
    suspend fun setOemGuideSeen() = store.edit { it[K.oemGuideSeen] = true }

    suspend fun setMonitoringDesired(on: Boolean) = store.edit {
        it[K.monitoringDesired] = on
        if (on) it[K.monitoringStartedAt] = System.currentTimeMillis()
    }

    /** Stable id of *this* phone when acting as a camera. Created lazily, survives sign-out. */
    suspend fun cameraId(): String {
        store.data.first()[K.cameraId]?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { if (it[K.cameraId] == null) it[K.cameraId] = id }
        return store.data.first()[K.cameraId] ?: id
    }

    suspend fun updateCamera(transform: (CameraSettings) -> CameraSettings) = store.edit { p ->
        val cur = CameraSettings.fromJson(p[K.camera]?.let { runCatching { JSONObject(it) }.getOrNull() })
        p[K.camera] = transform(cur).copy(updatedAtMs = System.currentTimeMillis()).toJson().toString()
    }

    /** Apply settings pushed from a viewer without bumping the timestamp. */
    suspend fun applyRemoteCamera(remote: CameraSettings) = store.edit { it[K.camera] = remote.toJson().toString() }

    suspend fun updateNotifications(transform: (NotificationPrefs) -> NotificationPrefs) = store.edit { p ->
        val cur = NotificationPrefs.fromJson(p[K.notif]?.let { runCatching { JSONObject(it) }.getOrNull() })
        p[K.notif] = transform(cur).toJson().toString()
    }

    suspend fun updateRecording(transform: (RecordingSettings) -> RecordingSettings) = store.edit { p ->
        val cur = RecordingSettings.fromJson(p[K.recording]?.let { runCatching { JSONObject(it) }.getOrNull() })
        p[K.recording] = transform(cur).toJson().toString()
    }

    suspend fun updateNetwork(transform: (NetworkSettings) -> NetworkSettings) = store.edit { p ->
        val cur = NetworkSettings.fromJson(p[K.network]?.let { runCatching { JSONObject(it) }.getOrNull() })
        p[K.network] = transform(cur).toJson().toString()
    }

    /** Wipe user-specific data on logout (keeps theme + camera id). */
    suspend fun clearUserData() = store.edit {
        it.remove(K.selectedCameraId); it.remove(K.monitoringDesired)
    }
}
