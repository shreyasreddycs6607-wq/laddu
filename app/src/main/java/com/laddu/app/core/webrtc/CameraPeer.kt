package com.laddu.app.core.webrtc

import com.laddu.app.core.firebase.IceCandidateDoc
import com.laddu.app.core.firebase.SessionDescriptionDoc
import com.laddu.app.core.firebase.SessionState
import com.laddu.app.core.firebase.SignalingRepository
import com.laddu.app.core.model.StreamQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera side of ONE viewer session. The camera is the offerer: it adds its (shared) video and
 * audio tracks, creates the SDP offer, publishes it to Firestore, applies the viewer's answer and
 * trickles ICE candidates both ways. Cleans up fully on [close].
 */
class CameraPeer(
    private val cameraId: String,
    val sessionId: String,
    private val factory: PeerConnectionFactory,
    private val config: PeerConnection.RTCConfiguration,
    private val video: VideoTrack,
    private val audio: AudioTrack?,
    private val signaling: SignalingRepository,
    private val onClosed: (CameraPeer) -> Unit,
) {
    // An uncaught failure in any child must end this peer, not crash the camera service.
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> close() })
    private var pc: PeerConnection? = null
    private var videoSender: RtpSender? = null
    private val closed = AtomicBoolean(false)
    private val pendingRemote = ArrayList<IceCandidate>()
    @Volatile private var remoteSet = false
    private var graceJob: Job? = null

    @Volatile var quality: StreamQuality = StreamQuality.MEDIUM
        private set
    @Volatile var connected = false
        private set
    var requestedQuality: StreamQuality = StreamQuality.MEDIUM

    fun start(initial: StreamQuality) {
        quality = initial
        scope.launch {
            try {
                val conn = factory.createPeerConnection(config, observer) ?: error("PeerConnection creation failed")
                pc = conn
                videoSender = conn.addTrack(video, listOf("laddu"))
                audio?.let { conn.addTrack(it, listOf("laddu")) }
                applyQuality(initial, initial)

                val offer = conn.createOfferSuspend()
                conn.setLocalSuspend(offer)
                signaling.setOffer(cameraId, sessionId, SessionDescriptionDoc(offer.type.canonicalForm(), offer.description))

                launch { // viewer's answer + remote state
                    signaling.observeSession(cameraId, sessionId).collect { doc ->
                        if (doc == null || doc.state == SessionState.ENDED || doc.state == SessionState.FAILED) { close(); return@collect }
                        if (!remoteSet && doc.answer != null) {
                            try {
                                conn.setRemoteSuspend(SessionDescription(SessionDescription.Type.fromCanonicalForm(doc.answer.type), doc.answer.sdp))
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { close(); return@collect }
                            // flip the flag and drain under the same lock the candidate path uses, so none is lost
                            val queued = synchronized(pendingRemote) { remoteSet = true; pendingRemote.toList().also { pendingRemote.clear() } }
                            queued.forEach { conn.addIceCandidate(it) }
                        }
                        StreamQuality.entries.firstOrNull { it.name == doc.quality }?.let { requestedQuality = it }
                    }
                }
                launch {
                    signaling.observeCandidates(cameraId, sessionId, fromCamera = false).collect { c ->
                        val ice = IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)
                        val ready = synchronized(pendingRemote) { if (remoteSet) true else { pendingRemote += ice; false } }
                        if (ready) conn.addIceCandidate(ice)
                    }
                }
                launch { // viewer never answered: free the slot
                    delay(45_000)
                    if (!connected) close()
                }
            } catch (t: Throwable) {
            android.util.Log.w("Laddu", "camera peer failed", t)
                close()
            }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            android.util.Log.i("Laddu", "camera ice candidate: ${c.sdp.substringAfter("typ ").take(6)} ${c.sdp.split(" ").getOrNull(4)}")
            scope.launch { signaling.addCandidate(cameraId, sessionId, true, IceCandidateDoc(c.sdpMid, c.sdpMLineIndex, c.sdp)) }
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            android.util.Log.i("Laddu", "camera ice state: $s")
            when (s) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                    graceJob?.cancel()
                    if (!connected) { connected = true; scope.launch { signaling.setState(cameraId, sessionId, SessionState.CONNECTED) } }
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    graceJob?.cancel()
                    graceJob = scope.launch { delay(15_000); close() } // viewer reconnects with a fresh session
                }
                // never close/dispose from inside the native callback
                PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED -> scope.launch { close() }
                else -> Unit
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
        override fun onAddTrack(r: org.webrtc.RtpReceiver, s: Array<out org.webrtc.MediaStream>) {}
    }

    /** Limits what THIS viewer receives: bitrate, frame rate and resolution relative to the source. */
    fun applyQuality(q: StreamQuality, sourceQuality: StreamQuality) {
        quality = q
        val sender = videoSender ?: return
        runCatching {
            val p = sender.parameters
            for (enc in p.encodings) {
                enc.maxBitrateBps = q.maxKbps * 1000
                enc.maxFramerate = q.fps
                enc.scaleResolutionDownBy = (sourceQuality.height.toDouble() / q.height).coerceAtLeast(1.0)
            }
            p.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.BALANCED
            sender.parameters = p
        }
    }

    /** Idempotent. Releases the peer connection and ends the session document. */
    fun close(signalEnd: Boolean = true) {
        if (!closed.compareAndSet(false, true)) return
        graceJob?.cancel()
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null; videoSender = null
        if (signalEnd) {
            // fire-and-forget on a short-lived scope: our own scope is cancelled right below
            kotlinx.coroutines.GlobalScope.launch { signaling.endSession(cameraId, sessionId) }
        }
        scope.cancel()
        onClosed(this)
    }
}
