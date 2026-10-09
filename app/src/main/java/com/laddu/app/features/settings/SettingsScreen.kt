package com.laddu.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.laddu.app.core.datastore.ThemeChoice
import com.laddu.app.core.model.AiPerformanceMode
import com.laddu.app.core.model.AppMode
import com.laddu.app.core.model.CameraFacing
import com.laddu.app.core.model.CameraSettings
import com.laddu.app.core.model.NetworkSettings
import com.laddu.app.core.model.NotificationPrefs
import com.laddu.app.core.model.RecordingSettings
import com.laddu.app.core.model.StreamQuality
import com.laddu.app.core.model.ThermalLevel
import com.laddu.app.core.model.VideoResolution
import com.laddu.app.core.ui.components.InfoCard
import com.laddu.app.core.ui.components.SectionTitle
import com.laddu.app.core.ui.components.StatusRow
import com.laddu.app.core.ui.components.Tone

class SettingsActions(
    val updateCamera: ((CameraSettings) -> CameraSettings) -> Unit,
    val updateNotifications: ((NotificationPrefs) -> NotificationPrefs) -> Unit,
    val updateRecording: ((RecordingSettings) -> RecordingSettings) -> Unit,
    val updateNetwork: ((NetworkSettings) -> NetworkSettings) -> Unit,
    val setTheme: (ThemeChoice) -> Unit,
    val deleteRecordings: () -> Unit,
    val restartCamera: () -> Unit,
    val signOut: () -> Unit,
    val switchMode: () -> Unit,
    val openOemGuide: () -> Unit,
    val updateSafety: ((com.laddu.app.core.safety.SafetyPolicy) -> com.laddu.app.core.safety.SafetyPolicy) -> Unit = {},
    val manageCamera: () -> Unit,
)

@Composable
fun SettingsScreen(
    onSignedOut: () -> Unit,
    onModeSwitched: () -> Unit,
    onOemGuide: () -> Unit,
    onManageCamera: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val actions = remember(vm) {
        SettingsActions(
            vm::updateCamera, vm::updateNotifications, vm::updateRecording, vm::updateNetwork, vm::setTheme, vm::deleteRecordings,
            restartCamera = { if (vm.ui.value.mode == AppMode.VIEWER) vm.restartRemoteCamera() else vm.restartLocalMonitoring() },
            signOut = { vm.signOut(onSignedOut) }, switchMode = { vm.switchMode(onModeSwitched) },
            openOemGuide = onOemGuide, manageCamera = onManageCamera, updateSafety = vm::updateSafety,
        )
    }
    SettingsContent(ui, actions)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(ui: SettingsUi, a: SettingsActions, modifier: Modifier = Modifier) {
    val viewer = ui.mode == AppMode.VIEWER
    val canEditCamera = !viewer || ui.remoteReady
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val cam = ui.camera

    // On tablets keep the form a readable width, centred, instead of stretching every control across the screen.
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings_screen")) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        if (viewer) {
            SectionTitle("Camera controls")
            InfoCard {
                Text(
                    if (ui.remoteReady) "Changes apply to ${ui.remoteCameraName ?: "your camera"} within seconds."
                    else "Pair and select a camera to change its settings remotely.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(a.restartCamera, enabled = ui.remoteReady, modifier = Modifier.testTag("restart_remote")) { Text("Restart monitoring") }
                    OutlinedButton(a.manageCamera, enabled = ui.remoteReady) { Text("Manage camera") }
                }
            }
        }

        // ---------------- Camera
        SectionTitle("Camera")
        InfoCard {
            ChoiceRow("Camera", CameraFacing.entries, cam.facing, { if (it == CameraFacing.BACK) "Rear" else "Front" }, canEditCamera) { v -> a.updateCamera { it.copy(facing = v) } }
            ChoiceRow("Video quality", VideoResolution.entries, cam.resolution, { it.label }, canEditCamera) { v -> a.updateCamera { it.copy(resolution = v) } }
            ChoiceRow("AI performance", AiPerformanceMode.entries, cam.aiMode, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, canEditCamera) { v -> a.updateCamera { it.copy(aiMode = v) } }
            Text(cam.aiMode.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow("Dog detection", cam.dogDetection, canEditCamera, "sw_dog") { v -> a.updateCamera { it.copy(dogDetection = v) } }
            SwitchRow("Movement detection", cam.movementDetection, canEditCamera, "sw_movement") { v -> a.updateCamera { it.copy(movementDetection = v) } }
            SwitchRow("Bark detection", cam.barkDetection, canEditCamera, "sw_bark") { v -> a.updateCamera { it.copy(barkDetection = v) } }
            SwitchRow("Howling / whining detection", cam.howlDetection, canEditCamera, "sw_howl") { v -> a.updateCamera { it.copy(howlDetection = v) } }
        }

        SectionTitle("AI sensitivity")
        InfoCard {
            SliderRow("Dog detection sensitivity", cam.dogSensitivity, canEditCamera, "slider_dog") { v -> a.updateCamera { it.copy(dogSensitivity = v) } }
            Text("Higher finds the dog more easily but may add false alerts.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Detection zones (bed, door, room) are planned for a future version.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Bark sensitivity")
        InfoCard {
            SliderRow("Bark sensitivity", cam.barkSensitivity, canEditCamera, "slider_bark") { v -> a.updateCamera { it.copy(barkSensitivity = v) } }
            StepperRow("Barks needed", cam.barkCount, 1..10, canEditCamera) { v -> a.updateCamera { it.copy(barkCount = v) } }
            StepperRow("...within (seconds)", cam.barkWindowSec, 5..120, canEditCamera, step = 5) { v -> a.updateCamera { it.copy(barkWindowSec = v) } }
        }

        // ---------------- Notifications
        SectionTitle("Notifications")
        InfoCard {
            val n = ui.notif
            SwitchRow("Barking", n.barking, true, "sw_n_bark") { v -> a.updateNotifications { it.copy(barking = v) } }
            SwitchRow("Repeated barking", n.repeatedBarking, true) { v -> a.updateNotifications { it.copy(repeatedBarking = v) } }
            SwitchRow("Movement", n.movement, true) { v -> a.updateNotifications { it.copy(movement = v) } }
            SwitchRow("Dog returned", n.dogReturned, true) { v -> a.updateNotifications { it.copy(dogReturned = v) } }
            SwitchRow("Camera offline", n.cameraOffline, true) { v -> a.updateNotifications { it.copy(cameraOffline = v) } }
            SwitchRow("Camera online", n.cameraOnline, true) { v -> a.updateNotifications { it.copy(cameraOnline = v) } }
            SwitchRow("Low battery", n.lowBattery, true) { v -> a.updateNotifications { it.copy(lowBattery = v) } }
            SwitchRow("Pet-safety alerts (hazards)", n.hazards, true, "sw_n_hazard") { v -> a.updateNotifications { it.copy(hazards = v) } }
            StepperRow("Minimum seconds between similar alerts", n.cooldownSec, 0..900, true, step = 30) { v -> a.updateNotifications { it.copy(cooldownSec = v) } }
        }

        // ---------------- Pet safety (hazards)
        SectionTitle("Pet safety")
        InfoCard {
            if (viewer) {
                Text("Pet-safety rules live on the camera phone. Open Laddu there to change them.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val p = ui.safety
                SwitchRow("Watch for hazards", p.enabled, true, "sw_safety") { v -> a.updateSafety { it.copy(enabled = v) } }
                SliderRow("Hazard sensitivity", p.sensitivity, p.enabled, "slider_safety") { v -> a.updateSafety { it.copy(sensitivity = v) } }
                SwitchRow("Alert on unidentified objects", p.unknownObjectAlerts, p.enabled) { v -> a.updateSafety { it.copy(unknownObjectAlerts = v) } }
                SwitchRow("Cloud second opinion (uploads one small snapshot per hazard)", p.cloudAnalysis, p.enabled, "sw_cloud_ai") { v -> a.updateSafety { it.copy(cloudAnalysis = v) } }
                if (p.cloudAnalysis) Text(
                    "When a hazard is detected, one reduced photo and a short text are sent to your own Firebase function for analysis. " +
                        "Local alerts never wait for it. Requires the server to be set up (docs/CLOUD_AI.md).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Laddu reports what the camera appears to show, with uncertainty. It cannot confirm that anything was swallowed " +
                        "and does not replace watching your dog or veterinary advice.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                p.items.filter { it.id != "unknown" }.forEach { item ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        if (item.id in com.laddu.app.core.safety.DefaultSafety.needsCustomModel) {
                            Text("Needs a model that can recognise this (the bundled one cannot).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                com.laddu.app.core.safety.Approval.APPROVED to "Approved",
                                com.laddu.app.core.safety.Approval.RESTRICTED to "Restricted",
                                com.laddu.app.core.safety.Approval.HAZARDOUS to "Hazardous",
                            ).forEach { (ap, label) ->
                                com.laddu.app.core.ui.components.ChoiceChip(item.approval == ap, {
                                    a.updateSafety { pol -> pol.copy(items = pol.items.map { i -> if (i.id == item.id) i.copy(approval = ap, risk = if (ap != com.laddu.app.core.safety.Approval.APPROVED && i.risk == com.laddu.app.core.safety.RiskLevel.INFO) com.laddu.app.core.safety.RiskLevel.CAUTION else i.risk, updatedAtMs = System.currentTimeMillis()) else i }) }
                                }, label, enabled = p.enabled)
                            }
                        }
                    }
                }
            }
        }

        // ---------------- Recording
        SectionTitle("Event recording")
        InfoCard {
            if (!viewer) {
                val r = ui.recording
                SwitchRow("Record event clips on this phone", r.enabled, true, "sw_rec") { v -> a.updateRecording { it.copy(enabled = v) } }
                SwitchRow("Upload clips & snapshots to cloud", r.uploadToCloud, true, "sw_upload") { v -> a.updateRecording { it.copy(uploadToCloud = v) } }
                SwitchRow("Upload on Wi-Fi only", r.wifiOnlyUpload, r.uploadToCloud) { v -> a.updateRecording { it.copy(wifiOnlyUpload = v) } }
                StepperRow("Storage limit (MB)", r.quotaMb, 50..5000, true, step = 50) { v -> a.updateRecording { it.copy(quotaMb = v) } }
                StepperRow("Keep for (days)", r.retentionDays, 1..60, true) { v -> a.updateRecording { it.copy(retentionDays = v) } }
                Text("Using ${ui.storageUsedMb} MB. Oldest files are removed automatically.", style = MaterialTheme.typography.bodyMedium)
            }
            StepperRow("Seconds before event", cam.clipPreSec, 0..10, canEditCamera) { v -> a.updateCamera { it.copy(clipPreSec = v) } }
            StepperRow("Seconds during event (max)", cam.clipDuringSec, 2..30, canEditCamera) { v -> a.updateCamera { it.copy(clipDuringSec = v) } }
            StepperRow("Seconds after event", cam.clipPostSec, 0..10, canEditCamera) { v -> a.updateCamera { it.copy(clipPostSec = v) } }
            if (!viewer) {
                OutlinedButton({ confirmDelete = true }, Modifier.padding(top = 8.dp).testTag("delete_recordings")) { Text("Delete all clips and snapshots") }
            }
        }

        // ---------------- Network
        SectionTitle("Network")
        InfoCard {
            ChoiceRow("Default live quality", StreamQuality.entries, ui.network.defaultQuality, { it.label }, true) { v -> a.updateNetwork { it.copy(defaultQuality = v) } }
            SwitchRow("Stream on Wi-Fi only", ui.network.streamOnWifiOnly, true, "sw_wifi_only") { v -> a.updateNetwork { it.copy(streamOnWifiOnly = v) } }
            SwitchRow("Always use relay server (hides IP, uses more data)", ui.network.forceRelay, true) { v -> a.updateNetwork { it.copy(forceRelay = v) } }
        }

        // ---------------- Privacy
        SectionTitle("Privacy")
        InfoCard {
            Text(
                "• The camera phone always shows when the camera and microphone are active.\n" +
                    "• Laddu never records secretly and never uploads video continuously.\n" +
                    "• Live video goes directly between your phones, encrypted.\n" +
                    "• Clips and snapshots stay on the camera phone unless you turn on cloud upload.\n" +
                    "• You can revoke any viewer at any time (Manage camera).",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // ---------------- Device info
        if (!viewer) {
            SectionTitle("Device information")
            InfoCard {
                StatusRow("Phone", ui.deviceName)
                StatusRow("System", ui.androidVersion)
                StatusRow("Battery", if (ui.health.batteryPct >= 0) "${ui.health.batteryPct}%${if (ui.health.charging) " (charging)" else ""}" else "n/a")
                StatusRow("Temperature", ui.health.temperatureC?.let { "%.1f°C".format(it) } ?: "n/a",
                    when (ui.health.thermal) { ThermalLevel.NORMAL -> Tone.GOOD; ThermalLevel.WARM -> Tone.WARN; ThermalLevel.HOT -> Tone.BAD })
                StatusRow("Firebase", if (ui.firebaseConfigured) "Configured" else "Not configured", if (ui.firebaseConfigured) Tone.GOOD else Tone.WARN)
                OutlinedButton(a.openOemGuide, Modifier.padding(top = 8.dp)) { Text("Oppo / ColorOS setup guide") }
            }
        }

        // ---------------- Appearance + Account + About
        SectionTitle("Appearance")
        InfoCard {
            ChoiceRow("Theme", ThemeChoice.entries, ui.theme, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, true) { a.setTheme(it) }
        }

        SectionTitle("Account")
        InfoCard {
            StatusRow("Signed in as", ui.email.ifBlank { "Not signed in" })
            StatusRow("Mode", if (viewer) "Viewer" else "Camera")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(a.switchMode, Modifier.testTag("switch_mode")) { Text("Switch mode") }
                OutlinedButton({ confirmSignOut = true }, Modifier.testTag("sign_out")) { Text("Log out") }
            }
        }

        SectionTitle("About Laddu")
        InfoCard {
            Text("Laddu ${ui.appVersion}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Smart AI dog monitoring. Detection runs on the camera phone; live video uses WebRTC; alerts use Firebase Cloud Messaging.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
    } // readable-width Box

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete all clips and snapshots?") },
        text = { Text("This removes every recording stored on this phone. Uploaded copies are not affected.") },
        confirmButton = { TextButton({ a.deleteRecordings(); confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Log out?") },
        text = { Text(if (viewer) "You will stop receiving alerts on this phone." else "Monitoring on this phone will stop.") },
        confirmButton = { TextButton({ confirmSignOut = false; a.signOut() }, Modifier.testTag("confirm_sign_out")) { Text("Log out") } },
        dismissButton = { TextButton({ confirmSignOut = false }) { Text("Cancel") } },
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, tag: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, enabled = enabled, modifier = if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
fun SliderRow(label: String, value: Float, enabled: Boolean, tag: String = "", onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(when { local < 0.34f -> "Low"; local < 0.67f -> "Medium"; else -> "High" }, style = MaterialTheme.typography.labelLarge)
        }
        Slider(local, { local = it }, enabled = enabled, onValueChangeFinished = { onChange(local) },
            modifier = if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
fun StepperRow(label: String, value: Int, range: IntRange, enabled: Boolean, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
        TextButton({ onChange((value - step).coerceAtLeast(range.first)) }, enabled = enabled && value > range.first) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        TextButton({ onChange((value + step).coerceAtMost(range.last)) }, enabled = enabled && value < range.last) { Text("+") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(label: String, options: List<T>, selected: T, name: (T) -> String, enabled: Boolean, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                // a clearly filled, ticked chip for the current choice (the default tint was easy to miss)
                FilterChip(
                    selected == o, { onSelect(o) }, label = { Text(name(o)) }, enabled = enabled,
                    leadingIcon = if (selected == o) {
                        { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, null, Modifier.size(androidx.compose.material3.FilterChipDefaults.IconSize)) }
                    } else null,
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }
}
