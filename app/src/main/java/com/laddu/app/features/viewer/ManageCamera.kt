package com.laddu.app.features.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.firebase.AuthRepository
import com.laddu.app.core.firebase.DeviceRepository
import com.laddu.app.core.model.CameraInfo
import com.laddu.app.core.model.ViewerAccess
import com.laddu.app.core.ui.components.EmptyState
import com.laddu.app.core.ui.components.InfoCard
import com.laddu.app.core.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ManageUi(val camera: CameraInfo? = null, val viewers: List<ViewerAccess> = emptyList(), val isOwner: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ManageCameraViewModel @Inject constructor(
    private val devices: DeviceRepository,
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)

    val ui: StateFlow<ManageUi> = combine(
        settings.selectedCameraId.flatMapLatest { id ->
            if (id == null) flowOf(null to emptyList())
            else combine(devices.observeCamera(id), devices.observeViewers(id)) { c, v -> c to v }
        },
        error,
    ) { (cam, viewers), err ->
        val uid = auth.currentUid
        val owner = cam != null && cam.ownerId == uid
        ManageUi(cam, if (owner) viewers else emptyList(), owner, err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageUi())

    fun rename(name: String) { val c = ui.value.camera ?: return; viewModelScope.launch { devices.rename(c.cameraId, name).onFailure { error.value = it.message } } }
    fun revoke(uid: String) { val c = ui.value.camera ?: return; viewModelScope.launch { devices.revokeViewer(c.cameraId, uid).onFailure { error.value = it.message } } }

    fun unpairOrLeave(onDone: () -> Unit) {
        val c = ui.value.camera ?: return
        viewModelScope.launch {
            val r = if (ui.value.isOwner) devices.unpairCamera(c.cameraId)
            else devices.leaveCamera(c.cameraId, auth.currentUid ?: return@launch)
            r.onSuccess { settings.setSelectedCamera(null); onDone() }.onFailure { error.value = it.message }
        }
    }
}

@Composable
fun ManageCameraScreen(onBack: () -> Unit, vm: ManageCameraViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<ViewerAccess?>(null) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("manage_camera")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Manage camera", style = MaterialTheme.typography.titleLarge)
        }
        val cam = ui.camera
        if (cam == null) { EmptyState("No camera selected", "Pair a camera first."); return@Column }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SectionTitle("Camera")
        InfoCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(cam.name, style = MaterialTheme.typography.titleMedium)
                if (ui.isOwner) TextButton({ renaming = true }) { Text("Rename") }
            }
            Text(if (ui.isOwner) "You own this camera." else "Shared with you.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ui.isOwner) {
            SectionTitle("Viewers with access")
            if (ui.viewers.isEmpty()) Text("Nobody else has access.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ui.viewers.forEach { v ->
                InfoCard(Modifier.padding(bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(v.displayName.ifBlank { v.email }); Text(v.email, style = MaterialTheme.typography.bodyMedium) }
                        IconButton({ revokeTarget = v }) { Icon(Icons.Filled.Delete, "Revoke", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton({ confirm = true }, Modifier.fillMaxWidth().testTag("unpair_or_leave")) {
            Text(if (ui.isOwner) "Unpair camera and remove all viewers" else "Remove this camera from my account", color = MaterialTheme.colorScheme.error)
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(ui.camera?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false }, title = { Text("Rename camera") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.rename(text); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(if (ui.isOwner) "Unpair this camera?" else "Remove camera?") },
        text = { Text(if (ui.isOwner) "Every viewer loses access immediately." else "You will no longer see this camera.") },
        confirmButton = { TextButton({ confirm = false; vm.unpairOrLeave(onBack) }) { Text("Confirm", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
    revokeTarget?.let { v ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null }, title = { Text("Revoke access?") },
            text = { Text("${v.displayName.ifBlank { v.email }} will lose access to this camera.") },
            confirmButton = { TextButton({ vm.revoke(v.userId); revokeTarget = null }) { Text("Revoke", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ revokeTarget = null }) { Text("Cancel") } },
        )
    }
}
