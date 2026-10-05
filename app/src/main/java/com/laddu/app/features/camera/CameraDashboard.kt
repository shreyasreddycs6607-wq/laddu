package com.laddu.app.features.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.laddu.app.core.ai.BoundingBox
import com.laddu.app.core.ai.ModelStatus
import com.laddu.app.core.camera.CameraEngine
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.firebase.AuthRepository
import com.laddu.app.core.model.ThermalLevel
import com.laddu.app.core.ui.components.ErrorBanner
import com.laddu.app.core.ui.components.InfoCard
import com.laddu.app.core.ui.components.SectionTitle
import com.laddu.app.core.ui.components.StatusPill
import com.laddu.app.core.ui.components.StatusRow
import com.laddu.app.core.ui.components.Tone
import com.laddu.app.core.ui.theme.StatusAmber
import com.laddu.app.core.ui.theme.StatusGreen
import com.laddu.app.core.ui.theme.StatusRed
import com.laddu.app.services.MonitorUi
import com.laddu.app.services.MonitoringController
import com.laddu.app.services.MonitoringStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which runtime permissions Laddu currently holds. */
data class PermissionState(val camera: Boolean, val microphone: Boolean, val notifications: Boolean) {
    /** Monitoring needs the camera. Without the mic we still run (bark detection off). */
    val canMonitor get() = camera
    val missing: List<String> get() = buildList {
        if (!camera) add(Manifest.permission.CAMERA)
        if (!microphone) add(Manifest.permission.RECORD_AUDIO)
        if (!notifications && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        fun read(ctx: Context) = PermissionState(
            camera = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            microphone = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notifications = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
}

data class CameraDashboardState(
    val monitor: MonitorUi = MonitorUi(),
    val desired: Boolean = false,
    val cloudEnabled: Boolean = true,
)

@HiltViewModel
class CameraDashboardViewModel @Inject constructor(
    private val holder: MonitoringStateHolder,
    private val settings: SettingsRepository,
    private val controller: MonitoringController,
    auth: AuthRepository,
    val engine: CameraEngine,
) : ViewModel() {

    val state: StateFlow<CameraDashboardState> =
        combine(holder.ui, settings.monitoringDesired, settings.localOnly) { m, d, local ->
            CameraDashboardState(m, d, cloudEnabled = !local)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraDashboardState())

    // The OS may have killed the process (crash, OEM cleanup); monitoring was still wanted, so bring it back.
    init { viewModelScope.launch { if (settings.monitoringDesired.first() && !holder.ui.value.running) controller.start() } }

    fun start() { viewModelScope.launch { controller.start() } }
    fun stop() { viewModelScope.launch { controller.stop() } }
}

@Composable
fun CameraDashboardScreen(
    onPairing: () -> Unit,
    onSettings: () -> Unit,
    onOemGuide: () -> Unit,
    vm: CameraDashboardViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    var perms by remember { mutableStateOf(PermissionState.read(ctx)) }

    // Re-read permissions whenever we come back (user may have used system settings).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) perms = PermissionState.read(ctx) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    var askedOnce by remember { mutableStateOf(false) }
    var startAfterGrant by remember { mutableStateOf(false) } // only START MONITORING may start monitoring, not "Grant permissions"
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = PermissionState.read(ctx)
        if (startAfterGrant && perms.canMonitor && it.isNotEmpty() && state.desired.not()) vm.start()
        startAfterGrant = false
    }
    val startRequested = { if (perms.canMonitor) vm.start() else { askedOnce = true; startAfterGrant = true; launcher.launch(perms.missing.toTypedArray()) } }

    CameraDashboardContent(
        state = state,
        perms = perms,
        permanentlyDenied = askedOnce && !perms.camera,
        onStart = startRequested,
        onStop = vm::stop,
        onGrant = { askedOnce = true; startAfterGrant = false; launcher.launch(perms.missing.toTypedArray()) },
        onOpenAppSettings = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) },
        onPairing = onPairing,
        onSettings = onSettings,
        onOemGuide = onOemGuide,
        preview = { LivePreview(vm.engine, state.monitor.dogBox) },
    )
}

@Composable
fun CameraDashboardContent(
    state: CameraDashboardState,
    perms: PermissionState,
    permanentlyDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onGrant: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onPairing: () -> Unit,
    onSettings: () -> Unit,
    onOemGuide: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
) {
    val m = state.monitor
    val s = m.status
    val header: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Laddu Camera", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (state.cloudEnabled) "Remote viewing enabled" else "Local mode (no cloud)",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    if (m.running) (if (m.internet) "Online" else "Offline (still monitoring)") else "Idle",
                    when { !m.running -> Tone.NEUTRAL; m.internet -> Tone.GOOD; else -> Tone.WARN },
                    Modifier.testTag("online_status"),
                )
            }

            if (m.running) PrivacyIndicators(m)

    }
    val previewBlock: @Composable () -> Unit = {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(20.dp)).background(Color.Black).testTag("preview_area"),
                contentAlignment = Alignment.Center,
            ) {
                if (m.running) preview()
                else Text("Live preview appears when monitoring starts", color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }

            m.error?.let { Spacer(Modifier.height(10.dp)); ErrorBanner(it, Modifier.testTag("camera_error")) }
            m.warning?.let { Spacer(Modifier.height(10.dp)); ErrorBanner(it) }

            if (!perms.camera || !perms.microphone) {
                Spacer(Modifier.height(10.dp))
                InfoCard(Modifier.testTag("permission_card")) {
                    Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            if (!perms.camera) append("• Camera: required to watch your dog.\n")
                            if (!perms.microphone) append("• Microphone: needed for bark detection. Monitoring still works without it.\n")
                        }.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(if (permanentlyDenied) onOpenAppSettings else onGrant, Modifier.testTag("grant_permissions")) {
                        Text(if (permanentlyDenied) "Open app settings" else "Grant permissions")
                    }
                }
            }

    }
    val statusBlock: @Composable () -> Unit = {
            SectionTitle("Status")
            InfoCard(Modifier.testTag("status_card")) {
                StatusRow("Dog AI", dogAiText(m), if (m.dogModel == ModelStatus.READY) Tone.GOOD else if (m.running) Tone.WARN else Tone.NEUTRAL)
                StatusRow("Dog", if (s.dogPresent) "Detected" else "Not seen", if (s.dogPresent) Tone.GOOD else Tone.NEUTRAL)
                StatusRow("Movement", if (s.moving) "Moving" else "Still", if (s.moving) Tone.WARN else Tone.NEUTRAL)
                StatusRow("Barking", barkText(m), if (s.barking) Tone.BAD else Tone.NEUTRAL)
                StatusRow("Internet", if (m.internet) "Connected" else "Offline", if (m.internet) Tone.GOOD else Tone.BAD)
                StatusRow(
                    "Battery",
                    if (s.batteryPct >= 0) "${s.batteryPct}%${if (s.charging) " ⚡ charging" else ""}" else "Unknown",
                    when { s.charging -> Tone.GOOD; s.batteryPct in 0..15 -> Tone.BAD; s.batteryPct in 16..30 -> Tone.WARN; else -> Tone.NEUTRAL },
                )
                StatusRow(
                    "Temperature",
                    (s.temperatureC?.let { "%.1f°C".format(it) } ?: "n/a") + when (s.thermal) { ThermalLevel.NORMAL -> ""; ThermalLevel.WARM -> " (warm)"; ThermalLevel.HOT -> " (HOT)" },
                    when (s.thermal) { ThermalLevel.NORMAL -> Tone.NEUTRAL; ThermalLevel.WARM -> Tone.WARN; ThermalLevel.HOT -> Tone.BAD },
                )
                if (m.running) {
                    StatusRow("Live viewers", m.liveViewers.toString(), if (m.liveViewers > 0) Tone.WARN else Tone.NEUTRAL)
                    StatusRow("Waiting to sync", "${m.pendingSync} events", if (m.pendingSync > 0) Tone.WARN else Tone.GOOD)
                    m.lastEvent?.let { StatusRow("Last event", it) }
                }
            }

    }
    val actionsBlock: @Composable () -> Unit = {
            Spacer(Modifier.height(16.dp))
            if (m.running || state.desired) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("stop_monitoring"),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed, contentColor = Color.White),
                ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("STOP MONITORING") }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("start_monitoring"),
                ) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("START MONITORING") }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.cloudEnabled) OutlinedButton(onPairing, Modifier.weight(1f).testTag("open_pairing")) {
                    Icon(Icons.Filled.QrCode2, null); Spacer(Modifier.width(6.dp)); Text("Pair viewer")
                }
                OutlinedButton(onSettings, Modifier.weight(1f).testTag("open_settings")) {
                    Icon(Icons.Filled.Settings, null); Spacer(Modifier.width(6.dp)); Text("Settings")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onOemGuide, Modifier.fillMaxWidth().testTag("open_oem_guide")) {
                Icon(Icons.Filled.BatteryChargingFull, null); Spacer(Modifier.width(6.dp)); Text("Keep running 24/7 (Oppo / ColorOS setup)")
            }
    }
    // Tablets get two columns (preview | status + actions); phones keep the single scrolling column.
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 720.dp
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("camera_dashboard"),
        ) {
            header()
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1.1f)) { previewBlock() }
                    Column(Modifier.weight(1f)) { statusBlock(); actionsBlock() }
                }
            } else {
                previewBlock(); statusBlock(); actionsBlock()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun dogAiText(m: MonitorUi) = when (m.dogModel) {
    ModelStatus.READY -> "Ready"
    ModelStatus.MODEL_MISSING -> "Model missing"
    ModelStatus.ERROR -> "Error"
    ModelStatus.NOT_LOADED -> if (m.running) "Loading..." else "Off"
}

private fun barkText(m: MonitorUi) = when {
    m.status.barking -> "Barking"
    m.audioModel == ModelStatus.MODEL_MISSING -> "Model missing"
    m.audioModel == ModelStatus.ERROR -> "Error"
    m.running && !m.micActive -> "Mic off"
    else -> "Quiet"
}

/** Always-visible disclosure of what the phone is capturing right now. */
@Composable
fun PrivacyIndicators(m: MonitorUi) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("privacy_indicators"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill("Camera active", if (m.cameraActive) Tone.BAD else Tone.NEUTRAL)
        StatusPill("Mic active", if (m.micActive) Tone.BAD else Tone.NEUTRAL)
        StatusPill("Monitoring", Tone.WARN)
    }
}

/** CameraX preview with the detected dog's bounding box drawn on top. */
@Composable
fun LivePreview(engine: CameraEngine, dogBox: BoundingBox?) {
    val ctx = LocalContext.current
    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    // Attach only while the screen is visible; frames keep flowing to the AI regardless.
    LifecycleResumeEffect(previewView) {
        engine.attachPreview(previewView.surfaceProvider)
        onPauseOrDispose { engine.attachPreview(null) }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        if (dogBox != null) Canvas(Modifier.fillMaxSize()) {
            drawRect(
                color = StatusGreen,
                topLeft = Offset(dogBox.left * size.width, dogBox.top * size.height),
                size = Size(dogBox.width * size.width, dogBox.height * size.height),
                style = Stroke(width = 4f),
            )
        }
    }
}
