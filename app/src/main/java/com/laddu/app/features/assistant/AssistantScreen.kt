package com.laddu.app.features.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laddu.app.core.firebase.EventRepository
import com.laddu.app.core.insights.AssistantAnswer
import com.laddu.app.core.insights.AssistantEngine
import com.laddu.app.core.insights.DaySummary
import com.laddu.app.core.model.LadduEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject

data class ChatMessage(val fromUser: Boolean, val text: String, val answer: AssistantAnswer? = null, val recordCount: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssistantViewModel @Inject constructor(private val events: EventRepository) : ViewModel() {
    private val camera = MutableStateFlow<String?>(null)
    private val engine = AssistantEngine()

    private val history: StateFlow<List<LadduEvent>> =
        camera.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else events.observeEvents(id, 300) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableStateFlow(
        listOf(ChatMessage(false, "Hi! Ask me about what your camera recorded. I only use the stored events, so I will tell you when there is nothing to go on.")),
    )
    val messages: StateFlow<List<ChatMessage>> = _messages

    fun setCamera(id: String?) { camera.value = id }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        val all = history.value
        val now = System.currentTimeMillis()
        val answer = engine.answer(q, all, now, previousDays(all, now))
        _messages.value = _messages.value + ChatMessage(true, q) + ChatMessage(false, answer.text, answer, all.size)
    }

    private fun previousDays(all: List<LadduEvent>, now: Long): List<DaySummary> {
        val start = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (1..7).map { d -> val s = start - d * 86_400_000L; DaySummary.of(all, s, s + 86_400_000L) }
    }
}

private val SUGGESTIONS = listOf("What happened today?", "Any hazard events today?", "Did my dog interact with plastic?", "When did my dog bark?", "Summarize the last hour")

@Composable
fun AssistantScreen(cameraId: String?, onOpenEvent: (String) -> Unit, vm: AssistantViewModel = hiltViewModel()) {
    LaunchedEffect(cameraId) { vm.setCamera(cameraId) }
    val messages by vm.messages.collectAsState()
    AssistantContent(messages, vm::ask, onOpenEvent, enabled = cameraId != null)
}

@Composable
fun AssistantContent(messages: List<ChatMessage>, onAsk: (String) -> Unit, onOpenEvent: (String) -> Unit, enabled: Boolean = true) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding().testTag("assistant_screen")) {
        Text("Ask Laddu", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
        Text(
            "Answers come from your camera's saved events, not from guesses.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages) { m -> Bubble(m, onOpenEvent) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGESTIONS.forEach { s -> AssistChip(onClick = { onAsk(s) }, label = { Text(s) }, enabled = enabled) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f).testTag("assistant_input"), enabled = enabled, singleLine = true,
                placeholder = { Text(if (enabled) "Ask about your dog…" else "Pair a camera first") },
            )
            IconButton({ onAsk(input); input = "" }, enabled = enabled && input.isNotBlank(), modifier = Modifier.testTag("assistant_send")) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, onOpenEvent: (String) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
                .background(if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp),
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyMedium)
            val a = m.answer
            if (a != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append(a.basis).append(" · ").append(m.recordCount).append(" saved events searched")
                        if (a.uncertain) append(" · includes possible, unconfirmed observations")
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                a.eventIds.take(3).forEachIndexed { i, id ->
                    Text(
                        "Open event ${i + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 6.dp).clickable { onOpenEvent(id) },
                    )
                }
            }
        }
    }
}
