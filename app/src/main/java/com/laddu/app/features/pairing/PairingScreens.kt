package com.laddu.app.features.pairing

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.firebase.AuthRepository
import com.laddu.app.core.firebase.AuthState
import com.laddu.app.core.firebase.DeviceRepository
import com.laddu.app.core.firebase.PairingRepository
import com.laddu.app.core.model.PairingSession
import com.laddu.app.core.model.ViewerAccess
import com.laddu.app.core.security.PairingToken
import com.laddu.app.core.ui.components.ErrorBanner
import com.laddu.app.core.ui.components.InfoCard
import com.laddu.app.core.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object QrCode {
    fun bitmap(payload: String, size: Int = 640): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val m = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) AColor.BLACK else AColor.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

// ============================================================ camera side

data class CameraPairingUi(
    val cameraId: String = "",
    val cameraName: String = "",
    val session: PairingSession? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val justPaired: Boolean = false,
    val viewers: List<ViewerAccess> = emptyList(),
    val unpaired: Boolean = false,
)

@HiltViewModel
class CameraPairingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
    private val devices: DeviceRepository,
    private val pairing: PairingRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(CameraPairingUi())
    val ui: StateFlow<CameraPairingUi> = _ui
    private var watch: Job? = null

    init { setup() }

    private fun setup() {
        viewModelScope.launch {
            val uid = auth.currentUid
            if (uid == null) { _ui.update { it.copy(loading = false, error = "Sign in to pair a viewer.") }; return@launch }
            val defaultName = com.laddu.app.core.firebase.defaultCameraName()
            var id = settings.cameraId()
            devices.ensureCamera(id, uid, defaultName) { settings.rotateCameraId() }.onSuccess { id = it }.onFailure { e ->
                _ui.update { it.copy(loading = false, error = e.message ?: "Could not register this camera") }; return@launch
            }
            _ui.update { it.copy(cameraId = id) }
            launch { devices.observeCamera(id).collect { c -> if (c != null) _ui.update { s -> s.copy(cameraName = c.name) } } }
            launch { devices.observeViewers(id).collect { v -> _ui.update { s -> s.copy(viewers = v) } } }
            regenerate()
        }
    }

    fun regenerate() {
        if (_ui.value.cameraId.isBlank()) { _ui.update { it.copy(loading = true, error = null) }; setup(); return } // registration failed earlier: retry it
        watch?.cancel()
        val id = _ui.value.cameraId
        val uid = auth.currentUid ?: return
        watch = viewModelScope.launch {
            _ui.value.session?.let { pairing.invalidate(it.token) }
            _ui.update { it.copy(loading = true, error = null, justPaired = false) }
            pairing.createSession(id, uid)
                .onSuccess { s ->
                    _ui.update { it.copy(session = s, loading = false) }
                    launch { pairing.observeUsed(s.token).collect { used -> if (used) _ui.update { it.copy(justPaired = true, session = null) } } }
                    delay((s.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0))
                    // expired: a stale QR must never stay on screen
                    if (!_ui.value.justPaired) regenerate()
                }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message ?: "Could not create pairing code") } }
        }
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { devices.rename(_ui.value.cameraId, name).onFailure { e -> _ui.update { it.copy(error = e.message) } } }
    }

    fun revoke(userId: String) {
        viewModelScope.launch { devices.revokeViewer(_ui.value.cameraId, userId).onFailure { e -> _ui.update { it.copy(error = e.message) } } }
    }

    fun unpair() {
        viewModelScope.launch {
            _ui.value.session?.let { pairing.invalidate(it.token) }
            devices.unpairCamera(_ui.value.cameraId)
                .onSuccess { _ui.update { it.copy(unpaired = true, session = null) } }
                .onFailure { e -> _ui.update { it.copy(error = e.message) } }
        }
    }

    override fun onCleared() {
        // never leave a valid token behind when the screen closes
        val t = _ui.value.session?.token
        if (t != null) kotlinx.coroutines.GlobalScope.launch { pairing.invalidate(t) }
    }
}

@Composable
fun CameraPairingScreen(onBack: () -> Unit, vm: CameraPairingViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    CameraPairingContent(ui, onBack, vm::regenerate, vm::rename, vm::revoke, vm::unpair)
}

@Composable
fun CameraPairingContent(
    ui: CameraPairingUi,
    onBack: () -> Unit,
    onRegenerate: () -> Unit,
    onRename: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onUnpair: () -> Unit,
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<ViewerAccess?>(null) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("camera_pairing")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Pair a viewer", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "On the viewer phone choose Viewer Mode, sign in, tap Add camera and scan this code. " +
                "The code works once and expires in 5 minutes. It contains no password.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        ui.error?.let { ErrorBanner(it, Modifier.testTag("pairing_error")); Spacer(Modifier.height(8.dp)) }

        Box(Modifier.fillMaxWidth().height(300.dp).testTag("qr_area"), contentAlignment = Alignment.Center) {
            val s = ui.session
            when {
                ui.justPaired -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✅ Viewer paired!", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("paired_ok"))
                    Spacer(Modifier.height(12.dp))
                    Button(onRegenerate) { Text("Pair another viewer") }
                }
                s != null -> {
                    val bmp = remember(s.token) { QrCode.bitmap(PairingToken.toQrPayload(s.token)) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(bmp.asImageBitmap(), "Pairing QR code", Modifier.size(240.dp).testTag("qr_image"), filterQuality = FilterQuality.None)
                        Countdown(s.expiresAtMs, nowMs)
                    }
                }
                ui.loading -> CircularProgressIndicator()
                else -> OutlinedButton(onRegenerate) { Text("Try again") }
            }
        }

        SectionTitle("This camera")
        InfoCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(ui.cameraName.ifBlank { "Laddu camera" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("camera_name"))
                TextButton({ renaming = true }, Modifier.testTag("rename_camera")) { Text("Rename") }
            }
        }

        SectionTitle("Viewers with access")
        if (ui.viewers.isEmpty()) Text("No viewers paired yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ui.viewers.forEach { v ->
            InfoCard(Modifier.padding(bottom = 8.dp).testTag("viewer_${v.userId}")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(v.displayName.ifBlank { v.email }, style = MaterialTheme.typography.titleMedium)
                        Text(v.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ revokeTarget = v }) { Icon(Icons.Filled.Delete, "Revoke access", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton({ confirmUnpair = true }, Modifier.fillMaxWidth().testTag("unpair_camera")) {
            Text("Unpair camera and remove all viewers", color = MaterialTheme.colorScheme.error)
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(ui.cameraName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename camera") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.testTag("rename_field")) },
            confirmButton = { TextButton({ onRename(text); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmUnpair) AlertDialog(
        onDismissRequest = { confirmUnpair = false },
        title = { Text("Unpair this camera?") },
        text = { Text("All viewers lose access immediately and the camera is removed from your account.") },
        confirmButton = { TextButton({ onUnpair(); confirmUnpair = false }) { Text("Unpair", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmUnpair = false }) { Text("Cancel") } },
    )
    revokeTarget?.let { v ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke access?") },
            text = { Text("${v.displayName.ifBlank { v.email }} will no longer be able to see this camera.") },
            confirmButton = { TextButton({ onRevoke(v.userId); revokeTarget = null }) { Text("Revoke", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ revokeTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Countdown(expiresAtMs: Long, nowMs: () -> Long) {
    var left by remember(expiresAtMs) { mutableLongStateOf((expiresAtMs - nowMs()) / 1000) }
    LaunchedEffect(expiresAtMs) {
        while (left > 0) { delay(1000); left = (expiresAtMs - nowMs()) / 1000 }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        if (left > 0) "Expires in ${left / 60}:${"%02d".format(left % 60)}" else "Expired, refreshing...",
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("qr_countdown"),
    )
}

// ============================================================ viewer side

data class AddCameraUi(val busy: Boolean = false, val error: String? = null, val pairedCameraId: String? = null)

@HiltViewModel
class AddCameraViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val pairing: PairingRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(AddCameraUi())
    val ui: StateFlow<AddCameraUi> = _ui

    fun onScanned(raw: String?) {
        if (raw == null) return // user cancelled the scanner
        val token = PairingToken.parseQr(raw)
        if (token == null) { _ui.value = AddCameraUi(error = "This QR code is not a Laddu pairing code."); return }
        viewModelScope.launch {
            _ui.value = AddCameraUi(busy = true)
            val user = (auth.authState.first { it !is AuthState.Unknown } as? AuthState.SignedIn)?.user
            if (user == null) { _ui.value = AddCameraUi(error = "Please sign in first."); return@launch }
            pairing.redeem(token, user)
                .onSuccess { id -> settings.setSelectedCamera(id); _ui.value = AddCameraUi(pairedCameraId = id) }
                .onFailure { e -> _ui.value = AddCameraUi(error = pairingError(e)) }
        }
    }

    private fun pairingError(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("expired", true) || m.contains("already used", true) || m.contains("not valid", true) -> m
            m.contains("PERMISSION_DENIED", true) -> "This QR code has expired or was already used. Ask the camera phone to show a new one."
            else -> m.ifBlank { "Pairing failed. Check your Internet and try again." }
        }
    }
}

@Composable
fun AddCameraScreen(onBack: () -> Unit, onPaired: () -> Unit, vm: AddCameraViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { vm.onScanned(it.contents) }
    LaunchedEffect(ui.pairedCameraId) { if (ui.pairedCameraId != null) onPaired() }
    AddCameraContent(
        ui = ui, onBack = onBack,
        onScan = {
            scanner.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan the QR code shown on the Laddu camera phone")
                setBeepEnabled(false); setOrientationLocked(false)
            })
        },
    )
}

@Composable
fun AddCameraContent(ui: AddCameraUi, onBack: () -> Unit, onScan: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).testTag("add_camera")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Add camera", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "1. On the camera phone open Laddu → Camera Mode → Pair viewer.\n2. Scan the QR code it shows.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        ui.error?.let { ErrorBanner(it, Modifier.testTag("scan_error")); Spacer(Modifier.height(12.dp)) }
        Button(onScan, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("scan_qr")) {
            if (ui.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else { Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text("Scan QR code") }
        }
    }
}
