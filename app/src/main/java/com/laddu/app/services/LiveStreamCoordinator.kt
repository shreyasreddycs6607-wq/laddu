package com.laddu.app.services

import com.laddu.app.core.audio.resampleToMono
import com.laddu.app.core.camera.CameraEngine
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.firebase.SessionState
import com.laddu.app.core.firebase.SignalingRepository
import com.laddu.app.core.model.StreamQuality
import com.laddu.app.core.model.ThermalLevel
import com.laddu.app.core.webrtc.CameraPeer
import com.laddu.app.core.webrtc.CameraVideoFeeder
import com.laddu.app.core.webrtc.IceServerProvider
import com.laddu.app.core.webrtc.WebRtcFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camera-phone side of live streaming. Listens for viewer requests under
 * `devices/{cameraId}/liveSessions`, creates one [CameraPeer] per viewer (max [MAX_VIEWERS]),
 * shares ONE video source/track fed from the single CameraX stream, and releases everything when
 * the last viewer leaves.
 */
@Singleton
class LiveStreamCoordinator @Inject constructor(
    private val webrtc: WebRtcFactory,
    private val ice: IceServerProvider,
    private val signaling: SignalingRepository,
    private val cameraEngine: CameraEngine,
    private val settings: SettingsRepository,
) {
    companion object { const val MAX_VIEWERS = 2 }

    private val peers = ConcurrentHashMap<String, CameraPeer>()
    private val _active = MutableStateFlow(0)
    val activeViewers: StateFlow<Int> = _active

    /** Receives 16 kHz mono PCM while WebRTC owns the microphone (so bark detection keeps working). */
    @Volatile var onMicSamples: ((ShortArray, Int) -> Unit)? = null

    val audioOwnsMic: Boolean get() = _active.value > 0

    private var scope: CoroutineScope? = null
    private var cameraId = ""
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var feeder: CameraVideoFeeder? = null
    @Volatile private var thermalCap = StreamQuality.HIGH
    private var sourceQuality = StreamQuality.MEDIUM

    @Synchronized
    fun start(cameraId: String, ownerId: String) {
        if (scope != null) return
        this.cameraId = cameraId
        android.util.Log.i("Laddu", "live coordinator start camera=$cameraId owner=$ownerId")
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch { signaling.deleteStale(cameraId, 10 * 60_000L) }
        s.launch {
            while (isActive) { // the listener dies on any error (e.g. auth not ready yet): resubscribe
              runCatching { signaling.observeActiveSessions(cameraId).collect { docs ->
                android.util.Log.i("Laddu", "live sessions seen: ${docs.map { it.id.take(6) + ":" + it.state }}")
                val now = System.currentTimeMillis()
                for (d in docs) {
                    if (d.state == SessionState.ENDED || d.state == SessionState.FAILED) continue
                    if (peers.containsKey(d.id)) continue
                    if (d.state != SessionState.REQUESTED) continue
                    if (now - d.createdAtMs > 120_000) { signaling.endSession(cameraId, d.id); continue } // stale request
                    if (peers.size >= MAX_VIEWERS) { signaling.setState(cameraId, d.id, SessionState.FAILED); continue }
                    createPeer(d.id, d.quality)
                }
              } }
              delay(3_000)
            }
        }
        s.launch { // every few minutes drop abandoned sessions
            while (isActive) { delay(5 * 60_000L); signaling.deleteStale(cameraId, 10 * 60_000L) }
        }
        s.launch { // adapt quality to what viewers asked for
            while (isActive) { delay(2_000); retune() }
        }
    }

    private suspend fun createPeer(sessionId: String, requested: String) {
        android.util.Log.i("Laddu", "creating peer for session $sessionId")
        ensureMedia()
        val net = settings.networkSettings.first()
        val servers = ice.servers()
        val cfg = ice.rtcConfig(servers, net.forceRelay)
        val q = (StreamQuality.entries.firstOrNull { it.name == requested } ?: StreamQuality.MEDIUM).capped(thermalCap)
        val peer = CameraPeer(
            cameraId, sessionId, webrtc.factory, cfg, videoTrack!!, audioTrack, signaling,
            onClosed = { p -> peers.remove(p.sessionId); onPeersChanged() },
        )
        peers[sessionId] = peer
        onPeersChanged()
        peer.start(q)
    }

    /** Create the shared WebRTC tracks on first use. */
    @Synchronized
    private fun ensureMedia() {
        if (videoTrack != null) return
        val f = webrtc.factory
        val vs = f.createVideoSource(false)
        vs.capturerObserver.onCapturerStarted(true)
        videoSource = vs
        videoTrack = f.createVideoTrack("laddu-video", vs).also { it.setEnabled(true) }
        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("laddu-audio", audioSource).also { it.setEnabled(true) }
        feeder = CameraVideoFeeder(vs.capturerObserver).also { cameraEngine.addConsumer(it) }
        webrtc.micSink = { data, rate, ch ->
            val sink = onMicSamples
            if (sink != null) { val m = resampleToMono(data, rate, ch, 16_000); sink(m, m.size) }
        }
    }

    private fun onPeersChanged() {
        _active.value = peers.size
        feeder?.enabled = peers.isNotEmpty()
        retune()
        if (peers.isEmpty()) audioTrack?.setEnabled(true) // idle: no peer, nothing is captured
    }

    /** Source resolution = best quality any viewer wants (capped by heat); each peer is trimmed down individually. */
    private fun retune() {
        val list = peers.values.toList()
        if (list.isEmpty()) return
        val wanted = list.map { it.requestedQuality.capped(thermalCap) }
        val top = wanted.maxBy { it.ordinal }
        sourceQuality = top
        videoSource?.adaptOutputFormat(top.width, top.height, top.height, top.width, top.fps)
        feeder?.maxFps = top.fps
        list.forEach { it.applyQuality(it.requestedQuality.capped(thermalCap), top) }
    }

    fun onThermal(level: ThermalLevel) {
        thermalCap = when (level) {
            ThermalLevel.NORMAL -> StreamQuality.HIGH
            ThermalLevel.WARM -> StreamQuality.MEDIUM
            ThermalLevel.HOT -> StreamQuality.LOW
        }
        retune()
    }

    /** Graceful shutdown (monitoring stopped by the user). */
    suspend fun stop() {
        val toClose = peers.values.toList()
        toClose.forEach { it.close(signalEnd = true) }
        disposeMedia()
    }

    /** onDestroy path: release natively, no network. */
    fun stopBlocking() {
        peers.values.toList().forEach { it.close(signalEnd = false) }
        disposeMedia()
    }

    @Synchronized
    private fun disposeMedia() {
        scope?.cancel(); scope = null
        feeder?.let { it.enabled = false; cameraEngine.removeConsumer(it) }
        feeder = null
        webrtc.micSink = null
        runCatching { videoSource?.capturerObserver?.onCapturerStopped() }
        runCatching { videoTrack?.dispose() }; videoTrack = null
        runCatching { videoSource?.dispose() }; videoSource = null
        runCatching { audioTrack?.dispose() }; audioTrack = null
        runCatching { audioSource?.dispose() }; audioSource = null
        peers.clear()
        _active.value = 0
    }
}
