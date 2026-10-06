package com.laddu.app.features.live

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.firebase.SignalingRepository
import com.laddu.app.core.model.LiveState
import com.laddu.app.core.model.StreamQuality
import com.laddu.app.core.network.ConnectivityMonitor
import com.laddu.app.core.ui.components.ErrorBanner
import com.laddu.app.core.ui.components.StatusPill
import com.laddu.app.core.ui.components.Tone
import com.laddu.app.core.webrtc.IceServerProvider
import com.laddu.app.core.webrtc.ViewerSession
import com.laddu.app.core.webrtc.WebRtcFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import javax.inject.Inject

data class LiveUi(
    val state: LiveState = LiveState.IDLE,
    val message: String? = null,
    val muted: Boolean = false,
    val quality: StreamQuality = StreamQuality.MEDIUM,
    val video: VideoTrack? = null,
    val cameraOnline: Boolean = true,
    val blockedByWifiOnly: Boolean = false,
)

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val webrtc: WebRtcFactory,
    private val ice: IceServerProvider,
    private val signaling: SignalingRepository,
    private val settings: SettingsRepository,
    private val connectivity: ConnectivityMonitor,
) : ViewModel() {

    private var session: ViewerSession? = null
    private val sessionFlow = MutableStateFlow<ViewerSession?>(null)
    private val muted = MutableStateFlow(false)
    private val quality = MutableStateFlow(StreamQuality.MEDIUM)
    private val blocked = MutableStateFlow(false)
    private var cameraId: String? = null
    private var viewerId: String? = null
    private var cameraOnline = true
    private var mobileOkOnce = false // "use mobile data this time" really is one time
    val eglContext get() = webrtc.eglBase.eglBaseContext

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ui: StateFlow<LiveUi> = combine(
        sessionFlow.flatMapLatest { s -> if (s == null) flowOf(Triple(LiveState.IDLE, null as String?, null as VideoTrack?)) else combine(s.state, s.message, s.video, ::Triple) },
        muted, quality, blocked,
    ) { (st, msg, vid), m, q, b -> LiveUi(st, msg, m, q, vid, cameraOnline, b) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUi())

    init { viewModelScope.launch { quality.value = settings.networkSettings.first().defaultQuality } }

    fun setTarget(cameraId: String?, viewerId: String?, online: Boolean) {
        val wasOnline = cameraOnline
        cameraOnline = online
        if (wasOnline && !online) session?.cameraOffline() // camera stopped mid-stream: say so instead of ~100 s of retries
        if (this.cameraId != cameraId) { stop(); this.cameraId = cameraId }
        this.viewerId = viewerId
    }

    fun connect(force: Boolean = false) {
        val cam = cameraId ?: return
        val viewer = viewerId ?: return
        if (session != null && session!!.state.value != LiveState.FAILED && session!!.state.value != LiveState.ENDED) return
        viewModelScope.launch {
            val net = settings.networkSettings.first()
            if (net.streamOnWifiOnly && !mobileOkOnce && !connectivity.isWifiNow()) { blocked.value = true; return@launch }
            blocked.value = false
            val relay = net.forceRelay
            val s = ViewerSession(cam, viewer, webrtc, ice, signaling) { relay }.also {
                it.setMuted(muted.value)
            }
            session?.stop()
            session = s; sessionFlow.value = s
            if (!cameraOnline && !force) s.cameraOffline() else s.connect(quality.value)
        }
    }

    fun reconnect() { stop(); connect(force = true) } // user insists: try even if the heartbeat looks stale

    fun stop() {
        session?.stop(); session = null; sessionFlow.value = null
    }

    fun toggleMute() { muted.value = !muted.value; session?.setMuted(muted.value) }

    fun setQuality(q: StreamQuality) {
        quality.value = q; session?.setQuality(q)
        viewModelScope.launch { settings.updateNetwork { it.copy(defaultQuality = q) } }
    }

    fun allowOnMobileData() {
        mobileOkOnce = true; blocked.value = false; connect()
    }

    override fun onCleared() { stop() }
}

@Composable
fun LiveScreen(
    cameraId: String?,
    viewerId: String?,
    cameraName: String,
    cameraOnline: Boolean,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    vm: LiveViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val online by rememberUpdatedState(cameraOnline)
    // Stream only while the screen is visible; the camera phone is relieved as soon as we leave.
    // (Target is set in the same effect that connects, and heartbeat flaps do not restart the stream.)
    LifecycleStartEffect(cameraId, viewerId) {
        vm.setTarget(cameraId, viewerId, online)
        vm.connect()
        onStopOrDispose { vm.stop() }
    }
    HideSystemBars(fullscreen)
    // Back leaves fullscreen first; leaving the Live tab must never leave the shell stuck in fullscreen.
    androidx.activity.compose.BackHandler(enabled = fullscreen) { onFullscreenChange(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onFullscreenChange(false) } }

    LiveContent(
        ui = ui, cameraName = cameraName, fullscreen = fullscreen, eglContext = vm.eglContext,
        onToggleMute = vm::toggleMute, onQuality = vm::setQuality, onReconnect = vm::reconnect,
        onFullscreen = { onFullscreenChange(!fullscreen) }, onAllowMobile = vm::allowOnMobileData,
    )
}

@Composable
fun LiveContent(
    ui: LiveUi,
    cameraName: String,
    fullscreen: Boolean,
    eglContext: org.webrtc.EglBase.Context?,
    onToggleMute: () -> Unit,
    onQuality: (StreamQuality) -> Unit,
    onReconnect: () -> Unit,
    onFullscreen: () -> Unit,
    onAllowMobile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    Column(modifier.fillMaxSize().background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background).testTag("live_screen")) {
        if (!fullscreen) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cameraName.ifBlank { "Live" }, style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp), // a long camera name must not push the state pill off screen
                )
                StatusPill(stateLabel(ui.state), stateTone(ui.state), Modifier.testTag("live_state"))
            }
        }
        Box(
            Modifier.fillMaxWidth().then(if (fullscreen) Modifier.weight(1f) else Modifier.aspectRatio(4f / 3f)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (eglContext != null && ui.video != null) {
                VideoRenderer(ui.video, eglContext) { renderer = it }
            }
            if (ui.state != LiveState.LIVE) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    if (ui.state in setOf(LiveState.REQUESTING, LiveState.CONNECTING, LiveState.RECONNECTING)) CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        ui.message ?: when (ui.state) {
                            LiveState.REQUESTING -> "Contacting camera..."
                            LiveState.CONNECTING -> "Connecting video..."
                            LiveState.IDLE, LiveState.ENDED -> "Not connected"
                            else -> ""
                        },
                        color = Color.White, style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (ui.state == LiveState.LIVE) {
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    StatusPill("🔴 LIVE", Tone.BAD)
                }
            }
        }

        Column(Modifier.fillMaxWidth().then(if (fullscreen) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(12.dp)) {
            if (ui.blockedByWifiOnly) {
                ErrorBanner("Streaming is set to Wi-Fi only.")
                Spacer(Modifier.height(8.dp))
                Button(onAllowMobile) { Text("Use mobile data this time") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                ControlButton(if (ui.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, if (ui.muted) "Unmute" else "Mute", "mute_button", onToggleMute, fullscreen)
                ControlButton(Icons.Filled.CameraAlt, "Snapshot", "snapshot_button", {
                    renderer?.addFrameListener({ bmp -> saveSnapshot(ctx, bmp) }, 1f)
                }, fullscreen, enabled = ui.state == LiveState.LIVE)
                ControlButton(Icons.Filled.Refresh, "Reconnect", "reconnect_button", onReconnect, fullscreen)
                ControlButton(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Fullscreen", "fullscreen_button", onFullscreen, fullscreen)
            }
            if (!fullscreen) {
                Spacer(Modifier.height(12.dp))
                Text("Quality", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("quality_selector")) {
                    StreamQuality.entries.forEach { q ->
                        FilterChip(ui.quality == q, { onQuality(q) }, label = { Text("${q.label} (${q.height}p)") })
                    }
                }
                Text(
                    "Video and audio travel directly between your phones (encrypted). Lower quality uses less data.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit, dark: Boolean, enabled: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
            Icon(icon, label, tint = if (dark) Color.White else MaterialTheme.colorScheme.primary)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (dark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun stateLabel(s: LiveState) = when (s) {
    LiveState.IDLE -> "Idle"; LiveState.REQUESTING -> "Contacting..."; LiveState.CONNECTING -> "Connecting..."
    LiveState.LIVE -> "Live"; LiveState.RECONNECTING -> "Reconnecting..."; LiveState.FAILED -> "Failed"; LiveState.ENDED -> "Ended"
}

private fun stateTone(s: LiveState) = when (s) {
    LiveState.LIVE -> Tone.GOOD; LiveState.FAILED -> Tone.BAD
    LiveState.REQUESTING, LiveState.CONNECTING, LiveState.RECONNECTING -> Tone.WARN
    else -> Tone.NEUTRAL
}

/** WebRTC renderer wrapped for Compose; always detaches the sink and releases the EGL surface. */
@Composable
fun VideoRenderer(track: VideoTrack, eglContext: org.webrtc.EglBase.Context, onReady: (SurfaceViewRenderer) -> Unit = {}) {
    val ctx = LocalContext.current
    val view = remember(track) {
        SurfaceViewRenderer(ctx).apply {
            init(eglContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(view) {
        runCatching { track.addSink(view) } // the track may already be disposed by a teardown racing this composition
        onReady(view)
        onDispose {
            runCatching { track.removeSink(view) }
            view.release()
        }
    }
    AndroidView({ view }, Modifier.fillMaxSize().testTag("video_renderer"))
}

@Composable
private fun HideSystemBars(hide: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(hide) {
        val window = (ctx as? Activity)?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            if (hide) { c.hide(WindowInsetsCompat.Type.systemBars()); c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
            else c.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) } }
    }
}

/** Saves a frame to the gallery (Pictures/Laddu). */
fun saveSnapshot(ctx: Context, bmp: Bitmap) {
    val name = "laddu_${System.currentTimeMillis()}.jpg"
    val ok = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Laddu")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: error("no storage")
            java.io.File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
    }.isSuccess
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        Toast.makeText(ctx, if (ok) "Snapshot saved" else "Could not save snapshot", Toast.LENGTH_SHORT).show()
    }
}
