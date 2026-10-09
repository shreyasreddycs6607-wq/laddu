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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laddu.app.core.model.CameraInfo
import com.laddu.app.core.ui.components.CenteredLoading
import com.laddu.app.core.ui.components.EmptyState
import com.laddu.app.core.ui.components.InfoCard
import com.laddu.app.core.ui.components.QuickAction
import com.laddu.app.core.ui.components.StatusPill
import com.laddu.app.core.ui.components.StatusTile
import com.laddu.app.core.ui.components.Tone
import com.laddu.app.core.ui.theme.StatusAmber
import com.laddu.app.core.ui.theme.StatusGreen
import com.laddu.app.core.ui.theme.StatusRed
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
    onOpenSafety: () -> Unit = {},
    onOpenAssistant: () -> Unit = {},
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp).testTag("viewer_home")) {
        when {
            state.loading -> { Wordmark(); CenteredLoading() }
            state.cameras.isEmpty() -> {
                Wordmark()
                EmptyState("No camera yet", "Pair your camera phone to start watching your dog from anywhere.")
                Button(onAddCamera, Modifier.fillMaxWidth().height(52.dp).testTag("add_first_camera")) {
                    Icon(Icons.Filled.AddCircle, null); Spacer(Modifier.width(8.dp)); Text("Add camera")
                }
            }
            else -> {
                val cam = state.selected!!
                CameraHeader(state, onSelectCamera)
                Text(
                    friendlyStatus(state.online, cam.status),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).testTag("friendly_status"),
                )
                Spacer(Modifier.height(14.dp))
                PreviewCard(cam, state.online, onWatchLive)

                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QuickAction(Icons.Filled.PlayArrow, "Live", onWatchLive, highlighted = state.online, enabled = true)
                    QuickAction(Icons.Filled.Notifications, "Alerts", onOpenAlerts)
                    QuickAction(Icons.Filled.Shield, "Safety", onOpenSafety)
                    QuickAction(Icons.Filled.QuestionAnswer, "Ask", onOpenAssistant)
                    QuickAction(Icons.Filled.BarChart, "Activity", onOpenActivity)
                }

                Spacer(Modifier.height(22.dp))
                SectionHeader("Right now", if (state.online) null else "Camera offline: values may be out of date")
                InfoCard(Modifier.testTag("live_status")) {
                    val s = cam.status
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatusTile(Icons.Filled.Pets, "Dog", if (s.dogPresent) "Detected" else "Not seen", s.dogPresent, tone = Tone.GOOD)
                        StatusTile(Icons.Filled.DirectionsRun, "Movement", if (s.moving) "Moving" else "Still", s.moving, tone = Tone.WARN)
                        StatusTile(Icons.Filled.GraphicEq, "Barking", if (s.barking) "Barking" else "Quiet", s.barking, tone = Tone.BAD)
                        val low = s.batteryPct in 0..15 && !s.charging
                        StatusTile(
                            if (s.charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull, "Battery",
                            if (s.batteryPct >= 0) "${s.batteryPct}%" else "n/a", low || s.charging, tone = if (low) Tone.BAD else Tone.GOOD,
                        )
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionHeader("Today", null)
                InfoCard(Modifier.testTag("today_stats")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Stat("Movement", (today?.movementEvents ?: 0).toString())
                        Divider()
                        Stat("Barking", (today?.barkEvents ?: 0).toString())
                        Divider()
                        Stat("Dog seen", ActivityStats.formatDuration(today?.presenceMs ?: 0))
                    }
                }
                androidx.compose.material3.TextButton(onAddCamera, Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Filled.AddCircle, null); Spacer(Modifier.width(6.dp)); Text("Add another camera")
                }
            }
        }
    }
}

/** One plain sentence instead of making the owner read four tiles. */
internal fun friendlyStatus(online: Boolean, s: com.laddu.app.core.model.CameraStatus): String = when {
    !online -> "The camera is offline right now. What you see may be out of date."
    s.barking && s.dogPresent -> "Your dog is in view and barking."
    s.barking -> "Barking heard, but your dog is out of view."
    s.dogPresent && s.activity == "RUNNING" -> "Your dog is in view and moving quickly (running-like)."
    s.dogPresent && (s.activity == "WALKING" || s.moving) -> "Your dog is in view and moving around (walking-like)."
    s.dogPresent -> "Your dog is in view and calm."
    else -> "All quiet. Your dog is out of the camera's view."
}

@Composable
private fun Wordmark() {
    Text("Laddu", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SectionHeader(title: String, note: String?) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CameraHeader(state: ViewerState, onSelect: (String) -> Unit) {
    val cam = state.selected!!
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.weight(1f)) {
            Row(Modifier.clickable(enabled = state.cameras.size > 1) { open = true }.testTag("camera_selector"), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cam.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("camera_title"),
                )
                if (state.cameras.size > 1) Icon(Icons.Filled.ArrowDropDown, "Choose camera")
            }
            DropdownMenu(open, { open = false }) {
                state.cameras.forEach { c -> DropdownMenuItem({ Text(c.name) }, { open = false; onSelect(c.cameraId) }) }
            }
        }
        Spacer(Modifier.width(12.dp))
        StatusPill(if (state.online) "Online" else "Offline", if (state.online) Tone.GOOD else Tone.BAD, Modifier.testTag("online_pill"))
    }
}

/** The camera card: a dark 16:9 surface with a status badge and a play affordance, like a video thumbnail. */
@Composable
private fun PreviewCard(cam: CameraInfo, online: Boolean, onWatchLive: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2A44), Color(0xFF0B1220))))
            .clickable(onClick = onWatchLive).testTag("home_preview"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (online) Icons.Filled.Videocam else Icons.Filled.VideocamOff, null, tint = if (online) StatusGreen else StatusAmber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (online) "Ready" else "Offline", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(if (online) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, "Watch live", tint = if (online) MaterialTheme.colorScheme.onPrimary else Color.White, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(if (online) "Tap to watch live" else "Camera offline", color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (cam.lastSeenMs > 0) {
                Text(
                    "Last seen " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(cam.lastSeenMs)),
                    color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
