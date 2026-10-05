package com.laddu.app.features.alerts

import android.graphics.Bitmap
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laddu.app.core.firebase.EventRepository
import com.laddu.app.core.firebase.MediaRepository
import com.laddu.app.core.model.EventCategory
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import com.laddu.app.core.ui.components.CenteredLoading
import com.laddu.app.core.ui.components.EmptyState
import com.laddu.app.core.ui.components.InfoCard
import com.laddu.app.core.ui.components.SectionTitle
import com.laddu.app.core.ui.components.StatusRow
import com.laddu.app.core.ui.components.Tone
import com.laddu.app.features.activity.ActivityStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

enum class AlertFilter(val label: String) { ALL("All"), BARKING("Barking"), MOVEMENT("Movement"), DOG("Dog"), SYSTEM("System") }

fun List<LadduEvent>.filtered(f: AlertFilter): List<LadduEvent> = when (f) {
    AlertFilter.ALL -> this
    AlertFilter.BARKING -> filter { it.type.category == EventCategory.BARKING }
    AlertFilter.MOVEMENT -> filter { it.type.category == EventCategory.MOVEMENT }
    AlertFilter.DOG -> filter { it.type.category == EventCategory.DOG }
    AlertFilter.SYSTEM -> filter { it.type.category == EventCategory.SYSTEM }
}

fun eventEmoji(t: EventType) = when (t) {
    EventType.BARK -> "🔊"; EventType.REPEATED_BARK -> "⚠️"; EventType.HOWL -> "🐺"
    EventType.MOVEMENT -> "🐕"; EventType.DOG_PRESENCE -> "🐶"
    EventType.CAMERA_OFFLINE -> "📴"; EventType.CAMERA_ONLINE -> "✅"; EventType.LOW_BATTERY -> "🔋"
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlertsViewModel @Inject constructor(private val events: EventRepository) : ViewModel() {
    private val camera = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow(AlertFilter.ALL)
    fun setCamera(id: String?) { camera.value = id }
    fun setFilter(f: AlertFilter) { filter.value = f }

    val ui: StateFlow<Pair<AlertFilter, List<LadduEvent>?>> = combine(
        camera.flatMapLatest { id -> if (id == null) flowOf(emptyList<LadduEvent>()) else events.observeEvents(id, 300) }, filter,
    ) { list, f -> f to list?.filtered(f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertFilter.ALL to null)
}

@Composable
fun AlertsScreen(cameraId: String?, onOpen: (LadduEvent) -> Unit, vm: AlertsViewModel = hiltViewModel()) {
    LaunchedEffect(cameraId) { vm.setCamera(cameraId) }
    val (filter, list) = vm.ui.collectAsState().value
    AlertsContent(filter, list, vm::setFilter, onOpen)
}

@Composable
fun AlertsContent(filter: AlertFilter, events: List<LadduEvent>?, onFilter: (AlertFilter) -> Unit, onOpen: (LadduEvent) -> Unit) {
    Column(Modifier.fillMaxSize().testTag("alerts_screen")) {
        Text("Alerts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AlertFilter.entries.forEach { f -> FilterChip(filter == f, { onFilter(f) }, label = { Text(f.label) }, modifier = Modifier.testTag("filter_${f.name}")) }
        }
        when {
            events == null -> CenteredLoading()
            events.isEmpty() -> EmptyState("Nothing here yet", "Events from your camera will appear here as they happen.")
            else -> LazyColumn(Modifier.fillMaxSize().testTag("alerts_list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events, key = { it.eventId }) { e -> EventRow(e) { onOpen(e) } }
            }
        }
    }
}

@Composable
fun EventRow(e: LadduEvent, onClick: () -> Unit) {
    InfoCard(Modifier.clickable(onClick = onClick).testTag("event_${e.eventId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(eventEmoji(e.type), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.type.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.timestamp)) +
                        if (e.ongoing) " · ongoing" else if (e.durationMs >= 1000) " · ${ActivityStats.formatDuration(e.durationMs)}" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (e.clipRef != null || e.snapshotRef != null) Text("🎬", style = MaterialTheme.typography.titleLarge)
        }
    }
}

// ================================================================ details

data class EventDetailUi(
    val event: LadduEvent? = null,
    val loading: Boolean = true,
    val snapshot: Bitmap? = null,
    val clip: File? = null,
    val mediaError: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val events: EventRepository,
    private val media: MediaRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(EventDetailUi())
    val ui: StateFlow<EventDetailUi> = _ui
    private var loadedFor: String? = null

    fun load(eventId: String) {
        if (loadedFor == eventId) return
        loadedFor = eventId
        viewModelScope.launch {
            events.observeEvent(eventId).collect { e ->
                _ui.value = _ui.value.copy(event = e, loading = false)
                if (e != null && _ui.value.snapshot == null && e.snapshotRef != null) {
                    media.snapshot(e.snapshotRef).onSuccess { b -> _ui.value = _ui.value.copy(snapshot = b) }
                        .onFailure { _ui.value = _ui.value.copy(mediaError = "Snapshot unavailable") }
                }
            }
        }
    }

    fun loadClip() {
        val e = _ui.value.event ?: return
        val ref = e.clipRef ?: return
        viewModelScope.launch {
            media.clip(ref, e.eventId)
                .onSuccess { f -> _ui.value = _ui.value.copy(clip = f) }
                .onFailure { _ui.value = _ui.value.copy(mediaError = "Clip could not be downloaded") }
        }
    }
}

@Composable
fun EventDetailScreen(eventId: String, onBack: () -> Unit, onViewLive: (String) -> Unit, vm: EventDetailViewModel = hiltViewModel()) {
    LaunchedEffect(eventId) { vm.load(eventId) }
    val ui by vm.ui.collectAsState()
    EventDetailContent(ui, onBack, onViewLive, vm::loadClip)
}

@Composable
fun EventDetailContent(ui: EventDetailUi, onBack: () -> Unit, onViewLive: (String) -> Unit, onLoadClip: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("event_detail")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Alert details", style = MaterialTheme.typography.titleLarge)
        }
        val e = ui.event
        when {
            ui.loading -> CenteredLoading()
            e == null -> EmptyState("Event not found", "It may have been removed, or you may no longer have access.")
            else -> {
                Text("${eventEmoji(e.type)} ${e.type.label}", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("detail_type"))
                Spacer(Modifier.height(12.dp))
                InfoCard {
                    StatusRow("When", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(e.timestamp)))
                    StatusRow("Duration", if (e.ongoing) "ongoing" else if (e.durationMs > 0) ActivityStats.formatDuration(e.durationMs) else "instant")
                    StatusRow("Confidence", "${(e.confidence * 100).toInt()}%", if (e.confidence >= 0.7f) Tone.GOOD else Tone.WARN)
                    e.metadata.filterKeys { it !in setOf("localOnly", "source") }.forEach { (k, v) -> StatusRow(k.replaceFirstChar { it.uppercase() }, v) }
                }
                ui.snapshot?.let {
                    SectionTitle("Snapshot")
                    Image(it.asImageBitmap(), "Snapshot", Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).testTag("detail_snapshot"))
                }
                if (e.clipRef != null) {
                    SectionTitle("Clip")
                    if (ui.clip == null) Button(onLoadClip, Modifier.testTag("load_clip")) { Text("Load video clip") }
                    else ClipPlayer(ui.clip)
                }
                ui.mediaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (e.snapshotRef == null && e.clipRef == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No snapshot or clip was uploaded for this event. Enable cloud upload on the camera (Settings → Recording) to keep them.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Button({ onViewLive(e.cameraId) }, Modifier.fillMaxWidth().height(52.dp).testTag("view_live")) { Text("VIEW LIVE") }
            }
        }
    }
}

@Composable
private fun ClipPlayer(file: File) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(file.absolutePath)
                setMediaController(android.widget.MediaController(ctx).also { it.setAnchorView(this) })
                setOnPreparedListener { it.isLooping = false; start() }
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)).testTag("clip_player"),
    )
}
