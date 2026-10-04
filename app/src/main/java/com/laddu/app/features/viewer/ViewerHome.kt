package com.laddu.app.features.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.laddu.app.core.model.CameraInfo
import com.laddu.app.core.ui.components.CenteredLoading
import com.laddu.app.core.ui.components.EmptyState
import com.laddu.app.core.ui.components.InfoCard
import com.laddu.app.core.ui.components.SectionTitle
import com.laddu.app.core.ui.components.StatusPill
import com.laddu.app.core.ui.components.StatusRow
import com.laddu.app.core.ui.components.Tone
import com.laddu.app.features.activity.ActivityStats
import com.laddu.app.features.activity.DayStats
import java.text.DateFormat
import java.util.Date

@Composable
fun ViewerHomeContent(
    state: ViewerState,
    today: DayStats?,
    onSelectCamera: (String) -> Unit,
    onWatchLive: () -> Unit,
    onAddCamera: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("viewer_home")) {
        Text("🐕 Laddu", style = MaterialTheme.typography.headlineMedium)
        when {
            state.loading -> CenteredLoading()
            state.cameras.isEmpty() -> {
                EmptyState("No camera yet", "Pair your camera phone to start watching your dog from anywhere.")
                Button(onAddCamera, Modifier.fillMaxWidth().height(52.dp).testTag("add_first_camera")) {
                    Icon(Icons.Filled.AddCircle, null); Spacer(Modifier.width(8.dp)); Text("Add camera")
                }
            }
            else -> {
                val cam = state.selected!!
                CameraHeader(state, onSelectCamera)
                Spacer(Modifier.height(12.dp))
                PreviewCard(cam, state.online, state.nowMs, onWatchLive)

                SectionTitle("Right now")
                InfoCard(Modifier.testTag("live_status")) {
                    val s = cam.status
                    if (!state.online) Text("The camera is offline, so these values may be out of date.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    StatusRow("Dog", if (s.dogPresent) "Detected" else "Not seen", if (s.dogPresent) Tone.GOOD else Tone.NEUTRAL)
                    StatusRow("Movement", if (s.moving) "Moving" else "Still", if (s.moving) Tone.WARN else Tone.NEUTRAL)
                    StatusRow("Barking", if (s.barking) "Barking" else "Quiet", if (s.barking) Tone.BAD else Tone.GOOD)
                    StatusRow("Camera battery", if (s.batteryPct >= 0) "${s.batteryPct}%${if (s.charging) " ⚡" else ""}" else "n/a",
                        if (s.batteryPct in 0..15 && !s.charging) Tone.BAD else Tone.NEUTRAL)
                }

                SectionTitle("Today")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("today_stats")) {
                    StatCard("Movement", (today?.movementEvents ?: 0).toString(), Modifier.weight(1f))
                    StatCard("Barking", (today?.barkEvents ?: 0).toString(), Modifier.weight(1f))
                    StatCard("Dog seen", ActivityStats.formatDuration(today?.presenceMs ?: 0), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onOpenAlerts, Modifier.weight(1f)) { Text("Alerts") }
                    OutlinedButton(onOpenActivity, Modifier.weight(1f)) { Text("Activity") }
                }
            }
        }
    }
}

@Composable
private fun CameraHeader(state: ViewerState, onSelect: (String) -> Unit) {
    val cam = state.selected!!
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box {
            Row(Modifier.clickable(enabled = state.cameras.size > 1) { open = true }.testTag("camera_selector"), verticalAlignment = Alignment.CenterVertically) {
                Text(cam.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("camera_title"))
                if (state.cameras.size > 1) Icon(Icons.Filled.ArrowDropDown, "Choose camera")
            }
            DropdownMenu(open, { open = false }) {
                state.cameras.forEach { c -> DropdownMenuItem({ Text(c.name) }, { open = false; onSelect(c.cameraId) }) }
            }
        }
        StatusPill(if (state.online) "🟢 Online" else "🔴 Offline", if (state.online) Tone.GOOD else Tone.BAD, Modifier.testTag("online_pill"))
    }
}

@Composable
private fun PreviewCard(cam: CameraInfo, online: Boolean, nowMs: Long, onWatchLive: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1B1410))
            .clickable(onClick = onWatchLive).testTag("home_preview"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.PlayCircle, "Watch live", tint = Color.White, modifier = Modifier.height(56.dp).width(56.dp))
            Spacer(Modifier.height(6.dp))
            Text(if (online) "Tap to watch live" else "Camera offline", color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (cam.lastSeenMs > 0) {
                Text(
                    "Last seen " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(cam.lastSeenMs)),
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    InfoCard(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
