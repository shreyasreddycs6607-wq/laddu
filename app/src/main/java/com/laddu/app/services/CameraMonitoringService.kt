package com.laddu.app.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.laddu.app.core.ai.DogDetectionEngine
import com.laddu.app.core.ai.FramePipeline
import com.laddu.app.core.ai.confidenceThreshold
import com.laddu.app.core.ai.profileFor
import com.laddu.app.core.audio.AudioCapture
import com.laddu.app.core.audio.AudioWindower
import com.laddu.app.core.audio.BarkDetectionEngine
import com.laddu.app.core.audio.BarkPipeline
import com.laddu.app.core.camera.CameraEngine
import com.laddu.app.core.camera.CameraEngineState
import com.laddu.app.core.database.EventDao
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.device.DeviceHealth
import com.laddu.app.core.device.DeviceHealthMonitor
import com.laddu.app.core.events.DetectionConfig
import com.laddu.app.core.events.EngineInput
import com.laddu.app.core.events.EventEngine
import com.laddu.app.core.events.EventProcessor
import com.laddu.app.core.firebase.AuthRepository
import com.laddu.app.core.firebase.DeviceRepository
import com.laddu.app.core.firebase.LOCAL_OWNER
import com.laddu.app.core.firebase.RemoteSettingsRepository
import com.laddu.app.core.model.CameraSettings
import com.laddu.app.core.model.CameraStatus
import com.laddu.app.core.model.ThermalLevel
import com.laddu.app.core.network.ConnectivityMonitor
import com.laddu.app.core.notifications.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 24/7 monitoring as an Android *foreground service* (camera + microphone types) with a visible,
 * persistent notification and a Stop action. Nothing here bypasses Android's restrictions:
 * the service can only be (re)started while the user is interacting with Laddu or tapping our
 * notification, and after a reboot we ask the user to resume instead of starting silently.
 */
@AndroidEntryPoint
class CameraMonitoringService : LifecycleService() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var auth: AuthRepository
    @Inject lateinit var devices: DeviceRepository
    @Inject lateinit var remoteSettings: RemoteSettingsRepository
    @Inject lateinit var cameraEngine: CameraEngine
    @Inject lateinit var dogEngine: DogDetectionEngine
    @Inject lateinit var barkEngine: BarkDetectionEngine
    @Inject lateinit var processor: EventProcessor
    @Inject lateinit var health: DeviceHealthMonitor
    @Inject lateinit var connectivity: ConnectivityMonitor
    @Inject lateinit var notifier: NotificationHelper
    @Inject lateinit var holder: MonitoringStateHolder
    @Inject lateinit var dao: EventDao
    @Inject lateinit var live: LiveStreamCoordinator
    @Inject lateinit var clips: com.laddu.app.core.recording.ClipRecorder

    private var session: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val cameraLock = Mutex()
    private var shuttingDown = false

    companion object {
        const val ACTION_START = "com.laddu.app.action.START"
        const val ACTION_STOP = "com.laddu.app.action.STOP"
        const val ACTION_RESTART = "com.laddu.app.action.RESTART"
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A sticky restart after the OS killed us starts a camera/mic service from the background, which Android 11+
        // forbids: ask the user to resume instead (the dashboard also resumes on open).
        if (intent == null && Build.VERSION.SDK_INT >= 30) {
            notifier.showResumeNeeded(); stopSelf(startId); return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                // the notification's Stop must also clear the "want monitoring" flag, or the app would resume it
                lifecycleScope.launch(NonCancellable) { settings.setMonitoringDesired(false) }
                shutdown(); return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                if (session?.isActive == true) lifecycleScope.launch { restartSession() } else begin()
            }
            else -> begin()
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ start

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun begin() {
        if (session?.isActive == true) return
        shuttingDown = false
        val camOk = granted(Manifest.permission.CAMERA)
        val micOk = granted(Manifest.permission.RECORD_AUDIO)
        if (!enterForeground(camOk, micOk)) return
        // a failing worker must stop monitoring cleanly, not crash the whole app
        val onFailure = kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
            holder.update { it.copy(error = t.message ?: "Monitoring failed", running = false, starting = false) }
            shutdown()
        }
        session = lifecycleScope.launch(onFailure) {
            if (!settings.monitoringDesired.first()) { shutdown(); return@launch }
            try {
                runSession()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                holder.update { it.copy(error = t.message ?: "Monitoring failed", running = false, starting = false) }
                shutdown()
            }
        }
    }

    /** startForeground with only the service types whose runtime permission we really hold. */
    private fun enterForeground(camOk: Boolean, micOk: Boolean): Boolean {
        val n: Notification = notifier.monitoringNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                var type = 0
                if (camOk) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (micOk) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (type == 0) throw SecurityException("Camera permission not granted")
                ServiceCompat.startForeground(this, NotificationHelper.ID_MONITORING, n, type)
            } else {
                startForeground(NotificationHelper.ID_MONITORING, n)
            }
            true
        } catch (t: Throwable) {
            // e.g. started from the background on Android 12+, or permission missing
            holder.update { it.copy(error = "Could not start monitoring: ${t.message}") }
            notifier.showResumeNeeded()
            stopSelf()
            false
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "laddu:monitoring").apply { setReferenceCounted(false); acquire() }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            // low-latency mode only works on API 34+ with the screen off; older versions need high-perf to keep Wi-Fi awake
            val mode = if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "laddu:wifi").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null; wifiLock = null
    }

    // ------------------------------------------------------------------ session

    private lateinit var engine: EventEngine
    private lateinit var inputs: Channel<EngineInput>
    private lateinit var framePipeline: FramePipeline
    private lateinit var barkPipeline: BarkPipeline
    private lateinit var audio: AudioCapture
    private lateinit var windower: AudioWindower
    @Volatile private var current = CameraSettings()
    @Volatile private var healthNow = DeviceHealth()
    @Volatile private var onlineNow = true
    private var cameraId = ""
    private var lastCameraAttempt = 0L

    private suspend fun runSession() {
        holder.update { MonitorUi(starting = true) }
        acquireLocks()
        cameraId = settings.cameraId()
        val ownerId = auth.currentUid ?: LOCAL_OWNER
        if (ownerId != LOCAL_OWNER) { // the camera's devices/{id} record must exist before viewers' rules can resolve it
            val name = "Laddu Camera (${Build.MANUFACTURER.replaceFirstChar { c -> c.uppercase() }} ${Build.MODEL})"
            devices.ensureCamera(cameraId, ownerId, name) { settings.rotateCameraId() }.onSuccess { cameraId = it }
        }
        current = settings.cameraSettings.first()

        // Events left "ongoing" by a killed process would stay open forever: close and re-sync them first.
        if (dao.closeOngoing(System.currentTimeMillis()) > 0) processor.requestSync()
        engine = EventEngine(cameraId, ownerId, DetectionConfig.from(current))
        inputs = Channel(Channel.UNLIMITED)
        val emit: (EngineInput) -> Unit = { inputs.trySend(it) }

        framePipeline = FramePipeline(dogEngine, emit) { box -> holder.update { it.copy(dogBox = box) } }
        framePipeline.settings = current
        barkPipeline = BarkPipeline(barkEngine, emit)
        barkPipeline.settings = current
        windower = AudioWindower({ barkEngine.windowSamples }) { w -> barkPipeline.onWindow(w, System.currentTimeMillis()) }
        audio = AudioCapture(lifecycleScope, 16_000)
        live.onMicSamples = { d, n -> windower.push(d, n) }

        cameraEngine.addConsumer(framePipeline)
        processor.mediaHook = clips
        cameraEngine.addConsumer(clips)
        live.start(cameraId, ownerId)

        // Every worker below is a child of this scope, so cancelling the session stops them ALL
        // (e.g. no late "monitoring" heartbeat after the final "stopped" one).
        kotlinx.coroutines.supervisorScope {
            launchWorkers(this)

            applyRuntimeConfig(current, healthNow.thermal)
            startCamera(current)
            updateAudio(current)
            holder.update { it.copy(running = true, starting = false, error = null) }
            notifier.clearResumeNeeded()
            kotlinx.coroutines.awaitCancellation()
        }
    }

    private fun launchWorkers(scope: kotlinx.coroutines.CoroutineScope) {
        // 1) single consumer for ALL detection inputs: the EventEngine is not thread-safe
        scope.launch(Dispatchers.Default) {
            for (i in inputs) {
                val out = engine.onInput(i)
                if (out.isNotEmpty()) {
                    processor.handle(out)
                    out.lastOrNull()?.let { o -> holder.update { it.copy(lastEvent = "${o.event.type.label} · ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(o.event.timestamp))}") } }
                }
                publishStatus()
            }
        }
        // 2) clock so timers expire while nothing is detected
        scope.launch { while (isActive) { delay(1000); inputs.trySend(EngineInput.Tick(System.currentTimeMillis())) } }

        // 3) settings (local + remotely edited by a viewer)
        scope.launch {
            settings.cameraSettings.collectLatest { s ->
                val old = current
                current = s
                framePipeline.settings = s; barkPipeline.settings = s
                engine.config = DetectionConfig.from(s).copy(movementStartWindowMs = maxOf(5000L, framePipeline.profile.intervalMs * 4))
                if (old.facing != s.facing || old.resolution != s.resolution) startCamera(s)
                applyRuntimeConfig(s, healthNow.thermal)
                updateAudio(s)
            }
        }
        scope.launch {
            var baseline: Long? = null
            if (auth.currentUid != null) remoteSettings.ensure(cameraId, current) // viewers need settings/{id}.camera to exist
            remoteSettings.observe(cameraId).collect { r ->
                if (r == null) return@collect
                val (remote, restartAt) = r
                if (baseline == null) baseline = restartAt
                if (remote.updatedAtMs > current.updatedAtMs) settings.applyRemoteCamera(remote)
                if (restartAt > (baseline ?: 0L)) { baseline = restartAt; restartSession() }
            }
        }
        // 4) battery + thermal
        scope.launch {
            health.health.collect { h ->
                healthNow = h
                inputs.trySend(EngineInput.BatteryChanged(System.currentTimeMillis(), h.batteryPct, h.charging))
                applyRuntimeConfig(current, h.thermal)
                live.onThermal(h.thermal)
                clips.thermal = h.thermal
                holder.update {
                    it.copy(warning = when (h.thermal) {
                        ThermalLevel.NORMAL -> null
                        ThermalLevel.WARM -> "Phone is warm: AI and streaming are being reduced"
                        ThermalLevel.HOT -> "Phone is hot! AI paused to a minimum. Move it somewhere cooler"
                    })
                }
                publishStatus()
            }
        }
        // 5) connectivity -> events, sync, offline continuity
        scope.launch {
            connectivity.isOnline.collect { online ->
                onlineNow = online
                inputs.trySend(EngineInput.NetworkChanged(System.currentTimeMillis(), online))
                if (online) processor.requestSync()
                holder.update { it.copy(internet = online) }
            }
        }
        scope.launch { dao.pendingCount().collect { n -> holder.update { it.copy(pendingSync = n) } } }
        scope.launch { dogEngine.status.collect { s -> holder.update { it.copy(dogModel = s) } } }
        scope.launch { barkEngine.status.collect { s -> holder.update { it.copy(audioModel = s) } } }
        scope.launch {
            live.activeViewers.collect { n ->
                holder.update { it.copy(liveViewers = n) }
                // While a viewer is connected, WebRTC owns the microphone and feeds bark detection.
                if (n > 0) { audio.stop(); holder.update { it.copy(micActive = true) } } else updateAudio(current)
            }
        }

        // 6) heartbeat to the cloud (only when there is Internet; offline monitoring continues locally)
        scope.launch {
            var lastSent = 0L; var lastStatus: CameraStatus? = null
            while (isActive) {
                val s = currentStatus(true)
                val now = System.currentTimeMillis()
                if (onlineNow && (s != lastStatus && now - lastSent >= 5_000 || now - lastSent >= 30_000)) {
                    devices.heartbeat(cameraId, s)
                    lastSent = now; lastStatus = s
                }
                delay(2_000)
            }
        }
        // 7) camera watchdog
        scope.launch {
            while (isActive) {
                delay(10_000)
                val st = cameraEngine.state.value
                val stale = System.currentTimeMillis() - cameraEngine.lastFrameAtMs > 20_000
                if ((st is CameraEngineState.Error || (st is CameraEngineState.Running && stale)) && System.currentTimeMillis() - lastCameraAttempt > 10_000) {
                    startCamera(current)
                }
                // the microphone can fail (busy, read error, handed back by WebRTC): retry; no-ops while it is running
                if (live.activeViewers.value == 0) updateAudio(current)
            }
        }
        scope.launch { cameraEngine.state.collect { st -> publishCameraState(st) } }
        // 8) housekeeping
        scope.launch(Dispatchers.IO) { dao.deleteSyncedBefore(System.currentTimeMillis() - 30L * 24 * 3600 * 1000); clips.cleanup() }
    }

    private fun publishCameraState(st: CameraEngineState) {
        holder.update {
            when (st) {
                is CameraEngineState.Running -> it.copy(cameraActive = true, resolution = "${st.width}x${st.height}", error = null)
                is CameraEngineState.Error -> it.copy(cameraActive = false, error = st.message)
                else -> it.copy(cameraActive = false)
            }
        }
    }

    private fun currentStatus(monitoring: Boolean): CameraStatus {
        val live = if (::engine.isInitialized) engine.status(System.currentTimeMillis()) else null
        val ui = holder.ui.value
        return CameraStatus(
            monitoring = monitoring,
            dogPresent = live?.dogPresent ?: false,
            moving = live?.moving ?: false,
            barking = live?.barking ?: false,
            batteryPct = healthNow.batteryPct,
            charging = healthNow.charging,
            temperatureC = healthNow.temperatureC,
            thermal = healthNow.thermal,
            aiMode = current.aiMode,
            aiReady = ui.dogModel == com.laddu.app.core.ai.ModelStatus.READY,
            barkAiReady = ui.audioModel == com.laddu.app.core.ai.ModelStatus.READY,
        )
    }

    private fun publishStatus() {
        val s = currentStatus(true)
        holder.update { it.copy(status = s) }
    }

    /** Applies AI profile (mode x thermal) to the pipeline and (re)loads models when needed. */
    private fun applyRuntimeConfig(s: CameraSettings, thermal: ThermalLevel) {
        val profile = profileFor(s.aiMode, thermal)
        framePipeline.profile = profile
        engine.config = engine.config.copy(movementStartWindowMs = maxOf(5000L, profile.intervalMs * 4))
        lifecycleScope.launch(Dispatchers.Default) {
            if (s.dogDetection || s.movementDetection) dogEngine.load(profile.threads, confidenceThreshold(s.dogSensitivity))
            if (s.barkDetection || s.howlDetection) barkEngine.load()
        }
    }

    private suspend fun startCamera(s: CameraSettings) = cameraLock.withLock {
        lastCameraAttempt = System.currentTimeMillis()
        if (!granted(Manifest.permission.CAMERA)) {
            holder.update { it.copy(error = "Camera permission was removed. Open Laddu and grant it again.") }
            return@withLock
        }
        withContext(Dispatchers.Main) {
            runCatching { cameraEngine.start(this@CameraMonitoringService, s.facing, s.resolution) }
        }
        framePipeline.reset()
    }

    private fun updateAudio(s: CameraSettings) {
        val want = (s.barkDetection || s.howlDetection) && granted(Manifest.permission.RECORD_AUDIO)
        if (want && !audio.running && !live.audioOwnsMic) {
            audio.start { data, n -> windower.push(data, n) }
            lifecycleScope.launch {
                delay(1500)
                holder.update { it.copy(micActive = audio.active, warning = holder.ui.value.warning ?: audio.error?.let { e -> "Microphone: $e" }) }
            }
        } else if (!want && audio.running) {
            audio.stop(); holder.update { it.copy(micActive = false) }
        }
    }

    /** Full re-initialisation of camera + audio + models without leaving monitoring. */
    private suspend fun restartSession() {
        if (shuttingDown || !::audio.isInitialized) return // shutting down, or the session has not been built yet
        holder.update { it.copy(starting = true) }
        audio.stopAndJoin()
        cameraEngine.stop()
        dogEngine.close(); barkEngine.close()
        delay(500)
        if (shuttingDown) return
        applyRuntimeConfig(current, healthNow.thermal)
        startCamera(current)
        updateAudio(current)
        holder.update { it.copy(starting = false) }
    }

    // ------------------------------------------------------------------ stop

    /** Stop requested by the user (notification or app) or by an unrecoverable error. */
    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        val running = session
        lifecycleScope.launch(NonCancellable) {
            running?.cancel()
            running?.join()
            if (::engine.isInitialized) {
                runCatching { processor.handle(engine.flush(System.currentTimeMillis())) }
            }
            if (cameraId.isNotEmpty()) {
                runCatching { devices.heartbeat(cameraId, currentStatus(false)) }
            }
            teardown()
            holder.update { MonitorUi(error = it.error) } // keep a failure message visible after the shutdown
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun teardown() {
        runCatching { live.stop() }
        if (::audio.isInitialized) runCatching { audio.stopAndJoin() }
        withContext(Dispatchers.Main) {
            if (::framePipeline.isInitialized) cameraEngine.removeConsumer(framePipeline)
            cameraEngine.removeConsumer(clips)
            cameraEngine.stop()
        }
        processor.mediaHook = null
        runCatching { clips.stop() }
        if (::framePipeline.isInitialized) framePipeline.close()
        runCatching { dogEngine.close() }
        runCatching { barkEngine.close() }
        releaseLocks()
    }

    override fun onDestroy() {
        // The OS may destroy us without a user-requested stop; make sure nothing keeps running.
        session?.cancel()
        runCatching { cameraEngine.stop() }
        if (::audio.isInitialized) audio.stop()
        if (::framePipeline.isInitialized) framePipeline.close()
        runCatching { live.stopBlocking() }
        releaseLocks()
        super.onDestroy()
    }
}
