package com.laddu.app.core.webrtc

import com.laddu.app.core.firebase.IceCandidateDoc
import com.laddu.app.core.firebase.SessionDescriptionDoc
import com.laddu.app.core.firebase.SessionState
import com.laddu.app.core.firebase.SignalingRepository
import com.laddu.app.core.model.LiveState
import com.laddu.app.core.model.StreamQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Viewer side of a live session. Requests a session on the camera, receives its offer, answers,
 * exchanges ICE and exposes the remote [video]. Handles disconnects with bounded automatic
 * reconnects (see [LiveSessionStateMachine]) and releases every native object in [stop].
 */
class ViewerSession(
    private val cameraId: String,
    private val viewerId: String,
    private val webrtc: WebRtcFactory,
    private val ice: IceServerProvider,
    private val signaling: SignalingRepository,
    private val forceRelay: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> if (!stopped) fire(LiveEvent.IceFailed) })
    private val sm = LiveSessionStateMachine()

    private val _state = MutableStateFlow(LiveState.IDLE)
    val state: StateFlow<LiveState> = _state
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _video = MutableStateFlow<VideoTrack?>(null)
    val video: StateFlow<VideoTrack?> = _video

    private var pc: PeerConnection? = null
    private var sessionId: String? = null
    private var attempt: Job? = null
    private var reconnectJob: Job? = null
    private val beginLock = kotlinx.coroutines.sync.Mutex()
    private var graceJob: Job? = null
    private var audio: AudioTrack? = null
    private var muted = false
    private var quality = StreamQuality.MEDIUM
    private val pendingRemote = ArrayList<IceCandidate>()
    @Volatile private var remoteSet = false
    @Volatile private var stopped = false

    fun connect(q: StreamQuality) {
        quality = q
        stopped = false
        scope.launch { begin() }
    }

    fun setMuted(m: Boolean) { muted = m; audio?.setEnabled(!m) }

    fun setQuality(q: StreamQuality) {
        quality = q
        val sid = sessionId ?: return
        scope.launch { signaling.setQuality(cameraId, sid, q.name) }
    }

    fun reconnect() = fire(LiveEvent.UserReconnect)

    fun stop() {
        stopped = true
        fire(LiveEvent.UserStop) // tears down synchronously
        scope.cancel()
    }

    fun cameraOffline() = fire(LiveEvent.CameraOffline)

    /** All state-machine access goes through here: events arrive from the signaling, main and Default threads. */
    private fun fire(e: LiveEvent) { synchronized(sm) { apply(sm.onEvent(e)) } }

    private fun apply(step: LiveStep) {
        _state.value = step.state
        _message.value = step.message
        when (val a = step.action) {
            is LiveAction.Reconnect -> if (!stopped) {
                reconnectJob?.cancel() // one failure can raise several events; only the latest reconnect may run
                reconnectJob = scope.launch { delay(a.delayMs); if (!stopped) begin() }
            }
            // off the native callback thread unless we are being stopped (stop() runs on main and must be synchronous)
            LiveAction.Teardown -> if (stopped) teardown(endSession = true) else scope.launch { teardown(endSession = true) }
            LiveAction.None -> Unit
        }
    }

    private suspend fun begin() = beginLock.withLock { beginLocked() }

    private suspend fun beginLocked() {
        teardown(endSession = true)
        if (stopped) return
        fire(LiveEvent.RequestSent)
        try {
            val servers = ice.servers()
            val cfg = ice.rtcConfig(servers, forceRelay())
            val conn = webrtc.factory.createPeerConnection(cfg, observer) ?: error("Could not create connection")
            pc = conn
            remoteSet = false
            val sid = signaling.createSession(cameraId, viewerId, quality.name)
            android.util.Log.i("Laddu", "viewer created session $sid for camera=$cameraId")
            sessionId = sid

            attempt = scope.launch {
                launch {
                    var offerHandled = false
                    var seen = false
                    signaling.observeSession(cameraId, sid).collect { doc ->
                        if (doc == null) {
                            // the camera deleted the session (it closed the peer) or we lost access
                            if (seen && !stopped) fire(LiveEvent.IceFailed)
                            return@collect
                        }
                        seen = true
                        if (doc.state == SessionState.FAILED) { fire(LiveEvent.Timeout); return@collect }
                        if (doc.state == SessionState.ENDED && !stopped) { fire(LiveEvent.IceFailed); return@collect }
                        val offer = doc.offer
                        if (!offerHandled && offer != null) {
                            offerHandled = true
                            fire(LiveEvent.OfferReceived)
                            try {
                                conn.setRemoteSuspend(SessionDescription(SessionDescription.Type.fromCanonicalForm(offer.type), offer.sdp))
                                remoteSet = true
                                synchronized(pendingRemote) { pendingRemote.forEach { conn.addIceCandidate(it) }; pendingRemote.clear() }
                                val answer = conn.createAnswerSuspend()
                                conn.setLocalSuspend(answer)
                                signaling.setAnswer(cameraId, sid, SessionDescriptionDoc(answer.type.canonicalForm(), answer.description))
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
                                android.util.Log.w("Laddu", "viewer answer failed", t)
                                if (!stopped) fire(LiveEvent.IceFailed)
                            }
                        }
                    }
                }
                launch {
                    signaling.observeCandidates(cameraId, sid, fromCamera = true).collect { c ->
                        val ic = IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)
                        if (remoteSet) conn.addIceCandidate(ic) else synchronized(pendingRemote) { pendingRemote += ic }
                    }
                }
                launch { // the camera never answered / never connected
                    delay(30_000)
                    if (_state.value == LiveState.REQUESTING || _state.value == LiveState.CONNECTING) fire(LiveEvent.Timeout)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("Laddu", "viewer session failed", t)
            fire(LiveEvent.Timeout)
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            android.util.Log.i("Laddu", "viewer ice candidate: ${c.sdp.substringAfter("typ ").take(6)} ${c.sdp.split(" ").getOrNull(4)}")
            val sid = sessionId ?: return
            scope.launch { signaling.addCandidate(cameraId, sid, false, IceCandidateDoc(c.sdpMid, c.sdpMLineIndex, c.sdp)) }
        }

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            android.util.Log.i("Laddu", "viewer ice state: $s")
            when (s) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                    graceJob?.cancel(); fire(LiveEvent.IceConnected)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    fire(LiveEvent.IceDisconnected)
                    graceJob?.cancel()
                    graceJob = scope.launch { delay(8_000); fire(LiveEvent.DisconnectGraceExpired) }
                }
                PeerConnection.IceConnectionState.FAILED -> { graceJob?.cancel(); fire(LiveEvent.IceFailed) }
                else -> Unit
            }
        }

        override fun onTrack(t: RtpTransceiver) {
            android.util.Log.i("Laddu", "viewer got track ${t.receiver.track()?.kind()}")
            when (val track = t.receiver.track()) {
                is VideoTrack -> { track.setEnabled(true); _video.value = track }
                is AudioTrack -> { audio = track; track.setEnabled(!muted) }
            }
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: org.webrtc.MediaStream) {}
        override fun onRemoveStream(s: org.webrtc.MediaStream) {}
        override fun onDataChannel(d: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    /** Release the current attempt. Idempotent; safe to call from any state. */
    private fun teardown(endSession: Boolean) {
        graceJob?.cancel(); graceJob = null
        attempt?.cancel(); attempt = null
        _video.value = null
        audio = null
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
        remoteSet = false
        synchronized(pendingRemote) { pendingRemote.clear() }
        val sid = sessionId
        sessionId = null
        if (endSession && sid != null) {
            @Suppress("OPT_IN_USAGE")
            kotlinx.coroutines.GlobalScope.launch { signaling.endSession(cameraId, sid) }
        }
    }
}
