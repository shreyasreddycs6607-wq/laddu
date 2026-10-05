package com.laddu.app.core.webrtc

import android.content.Context
import com.laddu.app.BuildConfig
import com.laddu.app.core.firebase.FirebaseProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import com.laddu.app.core.firebase.awaitOrNull
import kotlinx.coroutines.tasks.await
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import javax.inject.Inject
import javax.inject.Singleton

/** Single PeerConnectionFactory + EGL context for the whole process (camera and viewer roles). */
@Singleton
class WebRtcFactory @Inject constructor(@ApplicationContext private val ctx: Context) {

    val eglBase: EglBase by lazy { EglBase.create() }

    /** Receives raw microphone PCM while a live session owns the mic (feeds bark detection). */
    @Volatile var micSink: ((ByteArray, Int, Int) -> Unit)? = null // data, sampleRate, channels

    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { s -> micSink?.invoke(s.data, s.sampleRate, s.channelCount) }
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }
}

/**
 * ICE servers. STUN is public. TURN credentials are NEVER compiled into the app by default:
 * they are fetched as short-lived credentials from the `getTurnCredentials` Cloud Function.
 * A static TURN from local.properties (dev only) is used as a fallback.
 */
@Singleton
class IceServerProvider @Inject constructor(private val fb: FirebaseProvider) {

    private data class Cached(val servers: List<PeerConnection.IceServer>, val expiresAtMs: Long)
    @Volatile private var cached: Cached? = null

    private val stun = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

    /** True when a relay is available (needed for different-network streaming on strict NATs). */
    @Volatile var hasTurn: Boolean = false
        private set

    suspend fun servers(): List<PeerConnection.IceServer> {
        cached?.takeIf { it.expiresAtMs > System.currentTimeMillis() + 60_000 }?.let { return it.servers }
        val turn = fetchEphemeralTurn() ?: devTurn()
        hasTurn = turn.isNotEmpty()
        val all = stun + turn
        cached = Cached(all, System.currentTimeMillis() + if (turn.isEmpty()) 60_000 else 5 * 3600_000L)
        return all
    }

    private suspend fun fetchEphemeralTurn(): List<PeerConnection.IceServer>? = runCatching {
        if (!fb.isConfigured || fb.currentUid == null) return null
        val data = fb.functions.getHttpsCallable("getTurnCredentials").call().awaitOrNull(8_000)?.getData() as? Map<*, *> ?: return null
        val urls = (data["urls"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val user = data["username"] as? String ?: return null
        val cred = data["credential"] as? String ?: return null
        if (urls.isEmpty()) return null
        listOf(PeerConnection.IceServer.builder(urls).setUsername(user).setPassword(cred).createIceServer())
    }.getOrNull()

    private fun devTurn(): List<PeerConnection.IceServer> {
        if (BuildConfig.DEV_TURN_URL.isBlank()) return emptyList()
        return listOf(
            PeerConnection.IceServer.builder(BuildConfig.DEV_TURN_URL.split(",").map { it.trim() })
                .setUsername(BuildConfig.DEV_TURN_USERNAME).setPassword(BuildConfig.DEV_TURN_CREDENTIAL).createIceServer()
        )
    }

    fun rtcConfig(servers: List<PeerConnection.IceServer>, forceRelay: Boolean): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Relay-only without a TURN server can never connect, so the switch only applies once one is configured.
            iceTransportsType = if (forceRelay && hasTurn) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // DTLS-SRTP is always on in WebRTC: media is end-to-end encrypted between the two phones.
        }
}
