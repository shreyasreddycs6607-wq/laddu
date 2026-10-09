package com.laddu.app.features.alerts

import android.graphics.Bitmap
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.laddu.app.core.ui.components.DayBar
import com.laddu.app.core.ui.theme.StatusAmber
import com.laddu.app.core.ui.theme.StatusGreen
import com.laddu.app.core.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
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


data class AlertsUi(val filter: AlertFilter = AlertFilter.ALL, val events: List<LadduEvent>? = null, val dayOffset: Int = 0, val dayStartMs: Long = 0L)

private fun dayStart(offset: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, -offset)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(offset: Int): String = when (offset) {
    0 -> "Today"
    1 -> "Yesterday"
    else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(dayStart(offset)))
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val events: EventRepository,
    private val media: MediaRepository,
) : ViewModel() {
    private val camera = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow(AlertFilter.ALL)
    private val dayOffset = MutableStateFlow(0)
    private val thumbs = ConcurrentHashMap<String, Bitmap>()

    fun setCamera(id: String?) { camera.value = id }
    fun setFilter(f: AlertFilter) { filter.value = f }
    fun previousDay() { dayOffset.value += 1 }
    fun nextDay() { if (dayOffset.value > 0) dayOffset.value -= 1 }

    /** Small cached snapshot for a list row (full-size snapshots are only fetched on the detail screen). */
    suspend fun thumbnail(ref: String): Bitmap? = thumbs[ref] ?: media.snapshot(ref).getOrNull()?.let { full ->
        val w = 320
        val scaled = if (full.width > w) Bitmap.createScaledBitmap(full, w, (full.height * w.toFloat() / full.width).toInt().coerceAtLeast(1), true) else full
        thumbs[ref] = scaled
        scaled
    }

    val ui: StateFlow<AlertsUi> = combine(
        camera.flatMapLatest { id -> if (id == null) flowOf(emptyList<LadduEvent>()) else events.observeEvents(id, 300) }, filter, dayOffset,
    ) { list, f, off ->
        val start = dayStart(off)
        val end = start + 24L * 3600_000
        AlertsUi(f, list.filter { it.timestamp in start until end }.filtered(f), off, start)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUi())
}

@Composable
fun AlertsScreen(cameraId: String?, onOpen: (LadduEvent) -> Unit, vm: AlertsViewModel = hiltViewModel()) {
    LaunchedEffect(cameraId) { vm.setCamera(cameraId) }
    val ui by vm.ui.collectAsState()
    AlertsContent(
        filter = ui.filter, events = ui.events, onFilter = vm::setFilter, onOpen = onOpen,
        dayLabel = dayLabel(ui.dayOffset), canGoNext = ui.dayOffset > 0, dayStartMs = ui.dayStartMs,
        onPrevDay = vm::previousDay, onNextDay = vm::nextDay, thumbnail = vm::thumbnail,
    )
}

@Composable
fun AlertsContent(
    filter: AlertFilter,
    events: List<LadduEvent>?,
    onFilter: (AlertFilter) -> Unit,
    onOpen: (LadduEvent) -> Unit,
    dayLabel: String = "Today",
    canGoNext: Boolean = false,
    dayStartMs: Long = dayStart(0),
    onPrevDay: () -> Unit = {},
    onNextDay: () -> Unit = {},
    thumbnail: suspend (String) -> Bitmap? = { null },
) {
    Column(Modifier.fillMaxSize().testTag("alerts_screen")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Alerts", style = MaterialTheme.typography.headlineMedium)
            DayBar(dayLabel, canGoNext, onPrevDay, onNextDay, Modifier.testTag("day_bar"))
        }
        Spacer(Modifier.height(12.dp))
        EventTimeline(events.orEmpty(), dayStartMs, isToday = !canGoNext, onOpen, Modifier.padding(horizontal = 16.dp).testTag("event_timeline"))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AlertFilter.entries.forEach { f -> com.laddu.app.core.ui.components.ChoiceChip(filter == f, { onFilter(f) }, f.label, Modifier.testTag("filter_${f.name}")) }
        }
        when {
            events == null -> CenteredLoading()
            events.isEmpty() -> EmptyState("Nothing here yet", "Events from your camera will appear here as they happen.")
            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("alerts_list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(events, key = { it.eventId }) { e -> EventRow(e, thumbnail) { onOpen(e) } }
            }
        }
    }
}

fun eventIcon(t: EventType): ImageVector = when (t) {
    EventType.BARK, EventType.HOWL -> Icons.Filled.GraphicEq
    EventType.REPEATED_BARK -> Icons.Filled.Warning
    EventType.MOVEMENT -> Icons.Filled.DirectionsRun
    EventType.DOG_PRESENCE -> Icons.Filled.Pets
    EventType.CAMERA_OFFLINE -> Icons.Filled.VideocamOff
    EventType.CAMERA_ONLINE -> Icons.Filled.Videocam
    EventType.LOW_BATTERY -> Icons.Filled.BatteryAlert
}

private fun categoryColor(c: EventCategory): Color = when (c) {
    EventCategory.BARKING -> StatusRed
    EventCategory.MOVEMENT -> StatusAmber
    EventCategory.DOG -> StatusGreen
    EventCategory.SYSTEM -> Color(0xFF7D8A96)
}

/**
 * A 24-hour strip for the selected day: one tick per event (coloured by category), a "now" line on today.
 * Tap near a tick to open that event.
 */
@Composable
fun EventTimeline(events: List<LadduEvent>, dayStartMs: Long, isToday: Boolean, onOpen: (LadduEvent) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val track = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.outline
    val now = MaterialTheme.colorScheme.primary
    val dayMs = 24L * 3600_000
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(events, dayStartMs) {
                    detectTapGestures { pos ->
                        val reach = with(density) { 22.dp.toPx() }
                        events.minByOrNull { abs((it.timestamp - dayStartMs).toFloat() / dayMs * size.width - pos.x) }
                            ?.takeIf { abs((it.timestamp - dayStartMs).toFloat() / dayMs * size.width - pos.x) <= reach }
                            ?.let(onOpen)
                    }
                },
        ) {
            for (h in 0..24 step 3) {
                val x = size.width * h / 24f
                drawLine(tick.copy(alpha = 0.5f), Offset(x, size.height - 8.dp.toPx()), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
            drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx())
            val w = 5.dp.toPx()
            events.forEach { e ->
                val x = ((e.timestamp - dayStartMs).toFloat() / dayMs * size.width).coerceIn(w, size.width - w)
                drawRoundRect(categoryColor(e.type.category), Offset(x - w / 2, 8.dp.toPx()), Size(w, size.height - 16.dp.toPx()), CornerRadius(w / 2))
            }
            if (isToday) {
                val x = ((System.currentTimeMillis() - dayStartMs).toFloat() / dayMs * size.width).coerceIn(0f, size.width)
                drawLine(now, Offset(x, 2.dp.toPx()), Offset(x, size.height - 2.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12 am", "6 am", "12 pm", "6 pm", "12 am").forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun EventRow(e: LadduEvent, thumbnail: suspend (String) -> Bitmap? = { null }, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("event_${e.eventId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EventThumb(e, thumbnail)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.type.label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.timestamp)) +
                    if (e.ongoing) " · ongoing" else if (e.durationMs >= 1000) " · ${ActivityStats.formatDuration(e.durationMs)}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Filled.Visibility, "View", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun EventThumb(e: LadduEvent, load: suspend (String) -> Bitmap?) {
    var bmp by remember(e.snapshotRef) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(e.snapshotRef) { e.snapshotRef?.let { bmp = load(it) } }
    val accent = categoryColor(e.type.category)
    Box(
        Modifier.size(width = 112.dp, height = 66.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(eventIcon(e.type), null, tint = accent, modifier = Modifier.size(28.dp))
        // a thin accent edge ties the thumbnail to the timeline colours
        Box(Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight().background(accent))
        if (e.clipRef != null) {
            Icon(
                Icons.Filled.PlayCircle, "Has clip", tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp),
            )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(eventIcon(e.type), null, tint = categoryColor(e.type.category), modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(e.type.label, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("detail_type"))
                }
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
