package com.laddu.app.features.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laddu.app.BuildConfig
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.datastore.ThemeChoice
import com.laddu.app.core.device.DeviceHealth
import com.laddu.app.core.device.DeviceHealthMonitor
import com.laddu.app.core.firebase.AuthRepository
import com.laddu.app.core.firebase.AuthState
import com.laddu.app.core.firebase.DeviceRepository
import com.laddu.app.core.firebase.FirebaseProvider
import com.laddu.app.core.firebase.RemoteSettingsRepository
import com.laddu.app.core.model.AppMode
import com.laddu.app.core.model.CameraSettings
import com.laddu.app.core.model.NetworkSettings
import com.laddu.app.core.model.NotificationPrefs
import com.laddu.app.core.model.RecordingSettings
import com.laddu.app.core.recording.ClipRecorder
import com.laddu.app.services.MonitoringController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUi(
    val mode: AppMode? = null,
    val email: String = "",
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val camera: CameraSettings = CameraSettings(),
    val notif: NotificationPrefs = NotificationPrefs(),
    val recording: RecordingSettings = RecordingSettings(),
    val network: NetworkSettings = NetworkSettings(),
    val remoteCameraName: String? = null,
    val remoteReady: Boolean = false,
    val storageUsedMb: Long = 0,
    val health: DeviceHealth = DeviceHealth(),
    val firebaseConfigured: Boolean = false,
    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val cameraId: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
    private val remote: RemoteSettingsRepository,
    devices: DeviceRepository,
    health: DeviceHealthMonitor,
    private val clips: ClipRecorder,
    private val controller: MonitoringController,
    private val monitor: com.laddu.app.services.MonitoringStateHolder,
    fb: FirebaseProvider,
) : ViewModel() {

    private val localCamera = settings.cameraSettings

    /** Settings of the camera being controlled from a viewer phone (null when none selected / not loaded). */
    private val remoteCamera = settings.selectedCameraId.flatMapLatest { id ->
        if (id == null) flowOf(null) else combine(remote.observe(id), devices.observeCamera(id)) { r, cam -> Triple(id, r, cam) }
    }.onStart { emit(null) } // never hold the whole screen back waiting for Firestore

    private data class Base(val mode: AppMode?, val email: String, val theme: ThemeChoice, val local: CameraSettings, val notif: NotificationPrefs)
    private data class Rest(val rec: RecordingSettings, val net: NetworkSettings, val health: DeviceHealth)

    private val base = combine(settings.appMode, auth.authState, settings.theme, localCamera, settings.notificationPrefs) { m, a, t, c, n ->
        Base(m, (a as? AuthState.SignedIn)?.user?.email.orEmpty(), t, c, n)
    }
    private val rest = combine(settings.recordingSettings, settings.networkSettings, health.health.onStart { emit(DeviceHealth()) }) { r, n, h -> Rest(r, n, h) }

    // clip storage is read off the main thread and only when it can change, not on every battery/thermal update
    private val usedMb = MutableStateFlow(0L)
    private fun refreshUsedMb() { viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { usedMb.value = clips.usedBytes() / (1024 * 1024) } }
    init { refreshUsedMb() }

    val ui: StateFlow<SettingsUi> = combine(base, rest, remoteCamera, usedMb) { b, r, rc, used ->
        val viewer = b.mode == AppMode.VIEWER
        SettingsUi(
            mode = b.mode, email = b.email, theme = b.theme,
            camera = if (viewer) (rc?.second?.first ?: CameraSettings()) else b.local,
            notif = b.notif, recording = r.rec, network = r.net,
            remoteCameraName = rc?.third?.name, remoteReady = viewer && rc?.second != null,
            storageUsedMb = used,
            health = r.health, firebaseConfigured = fb.isConfigured,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    fun updateCamera(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch {
            if (ui.value.mode == AppMode.VIEWER) {
                val id = settings.selectedCameraId.first() ?: return@launch
                remote.push(id, transform(ui.value.camera))
            } else settings.updateCamera(transform)
        }
    }

    fun updateNotifications(transform: (NotificationPrefs) -> NotificationPrefs) {
        viewModelScope.launch {
            settings.updateNotifications(transform)
            val uid = auth.currentUid ?: return@launch
            remote.pushNotificationPrefs(uid, settings.notificationPrefs.first())
        }
    }

    fun updateRecording(transform: (RecordingSettings) -> RecordingSettings) { viewModelScope.launch { settings.updateRecording(transform) } }
    fun updateNetwork(transform: (NetworkSettings) -> NetworkSettings) { viewModelScope.launch { settings.updateNetwork(transform) } }
    fun setTheme(t: ThemeChoice) { viewModelScope.launch { settings.setTheme(t) } }
    fun deleteRecordings() { viewModelScope.launch { withContext(kotlinx.coroutines.Dispatchers.IO) { clips.deleteAll() }; refreshUsedMb() } }

    fun restartRemoteCamera() {
        viewModelScope.launch { settings.selectedCameraId.first()?.let { remote.requestRestart(it) } }
    }

    fun restartLocalMonitoring() = controller.restart()

    /** Stops monitoring (if this is a camera), signs out and clears per-user local data. */
    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { controller.stop() }
            // let the service send its final "stopped" heartbeat while we are still signed in
            kotlinx.coroutines.withTimeoutOrNull(5_000) { monitor.ui.first { !it.running && !it.starting } }
            auth.signOut()
            settings.clearUserData()
            onDone()
        }
    }

    fun switchMode(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { controller.stop() }
            settings.clearAppMode()
            onDone()
        }
    }
}
