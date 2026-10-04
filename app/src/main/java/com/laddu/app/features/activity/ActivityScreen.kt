package com.laddu.app.features.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laddu.app.core.firebase.EventRepository
import com.laddu.app.core.ui.components.CenteredLoading
import com.laddu.app.core.ui.components.InfoCard
import com.laddu.app.core.ui.components.SectionTitle
import com.laddu.app.core.ui.theme.StatusAmber
import com.laddu.app.core.ui.theme.StatusRed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class ActivityRange(val label: String, val days: Int) { DAY("Today", 1), WEEK("7 days", 7) }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActivityViewModel @Inject constructor(private val events: EventRepository) : ViewModel() {
    private val camera = MutableStateFlow<String?>(null)
    private val range = MutableStateFlow(ActivityRange.WEEK)
    fun setCamera(id: String?) { camera.value = id }
    fun setRange(r: ActivityRange) { range.value = r }

    val ui: StateFlow<Pair<ActivityRange, List<DayStats>?>> = combine(camera, range) { c, r -> c to r }
        .flatMapLatest { (c, r) ->
            if (c == null) flowOf(r to null)
            else {
                val since = ActivityStats.startOfDay(System.currentTimeMillis() - (r.days - 1) * 86_400_000L - 3_600_000L)
                events.observeEvents(c, 2000, since).map { r to ActivityStats.compute(it, System.currentTimeMillis(), r.days) as List<DayStats>? }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityRange.WEEK to null)
}

@Composable
fun ActivityScreen(cameraId: String?, vm: ActivityViewModel = hiltViewModel()) {
    LaunchedEffect(cameraId) { vm.setCamera(cameraId) }
    val (range, days) = vm.ui.collectAsState().value
    ActivityContent(range, days, vm::setRange)
}

@Composable
fun ActivityContent(range: ActivityRange, days: List<DayStats>?, onRange: (ActivityRange) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("activity_screen")) {
        Text("Activity", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            ActivityRange.entries.forEach { r -> FilterChip(range == r, { onRange(r) }, label = { Text(r.label) }, modifier = Modifier.testTag("range_${r.name}")) }
        }
        if (days == null) { CenteredLoading(); return@Column }
        val total = days.fold(DayStats(0)) { a, d ->
            a.copy(presenceMs = a.presenceMs + d.presenceMs, movementEvents = a.movementEvents + d.movementEvents,
                barkEvents = a.barkEvents + d.barkEvents, howlEvents = a.howlEvents + d.howlEvents)
        }
        InfoCard(Modifier.testTag("activity_totals")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Total("Dog seen", ActivityStats.formatDuration(total.presenceMs))
                Total("Movement", total.movementEvents.toString())
                Total("Barking", total.barkEvents.toString())
                Total("Howling", total.howlEvents.toString())
            }
        }
        val fmt = SimpleDateFormat(if (days.size > 1) "EEE" else "d MMM", Locale.getDefault())
        val labels = days.map { fmt.format(Date(it.dayStartMs)) }
        SectionTitle("Dog presence (minutes)")
        BarChart(labels, days.map { it.presenceMs / 60_000f }, MaterialTheme.colorScheme.primary, "chart_presence")
        SectionTitle("Movement events")
        BarChart(labels, days.map { it.movementEvents.toFloat() }, StatusAmber, "chart_movement")
        SectionTitle("Barking events")
        BarChart(labels, days.map { it.barkEvents.toFloat() }, StatusRed, "chart_barking")
        if (days.all { it.presenceMs == 0L && it.movementEvents == 0 && it.barkEvents == 0 && it.howlEvents == 0 }) {
            Text("No activity recorded in this period yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun Total(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Dependency-free bar chart. */
@Composable
fun BarChart(labels: List<String>, values: List<Float>, color: Color, tag: String, modifier: Modifier = Modifier) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val axis = MaterialTheme.colorScheme.outlineVariant
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().testTag(tag)) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = slot * 0.55f
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), 2f)
            values.forEachIndexed { i, v ->
                val h = (v / max) * (size.height - 8f)
                drawRoundRect(color, Offset(i * slot + (slot - barW) / 2, size.height - h), Size(barW, h.coerceAtLeast(if (v > 0) 3f else 0f)), CornerRadius(6f, 6f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(l, style = MaterialTheme.typography.labelLarge, color = text)
                    Text(if (values[i] == values[i].toInt().toFloat()) values[i].toInt().toString() else "%.1f".format(values[i]), style = MaterialTheme.typography.bodyMedium, color = text)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
