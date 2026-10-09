package com.laddu.app.core.insights

import com.laddu.app.core.model.EventCategory
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * An answer built only from stored events. [eventIds] are the records it relied on (so the UI can open them);
 * [uncertain] is set when the answer rests on possible-interaction events rather than facts.
 */
data class AssistantAnswer(
    val text: String,
    val eventIds: List<String> = emptyList(),
    val basis: String = "Local detection on the camera phone",
    val uncertain: Boolean = false,
)

/**
 * Answers owner questions from the event history. It is rule-based on purpose: it can never invent an event,
 * works offline, and keeps private data on the device. A cloud model may later *rephrase* an answer, but the facts
 * always come from here.
 */
class AssistantEngine(private val zone: TimeZone = TimeZone.getDefault()) {

    private data class Range(val from: Long, val to: Long, val label: String, val wholeDay: Boolean = false)

    fun answer(question: String, events: List<LadduEvent>, nowMs: Long, previousDays: List<DaySummary> = emptyList()): AssistantAnswer {
        val q = question.lowercase(Locale.ROOT)
        val range = rangeFor(q, nowMs)
        val inRange = events.filter { it.timestamp in range.from..range.to }.sortedBy { it.timestamp }
        return when {
            has(q, "plastic", "swallow", "ate ", "eat", "chew", "hazard", "danger", "unsafe", "rubbish", "trash", "wrapper", "sock", "ingest", "safety", "interact") ->
                hazards(q, inRange, range)
            has(q, "bark", "howl", "whine", "noise", "loud") -> barking(inRange, range)
            has(q, "rest", "sleep", "inactive", "active", "activity", "moving", "move", "play") -> activity(events, range, nowMs)
            has(q, "offline", "online", "battery", "connected") -> camera(inRange, range)
            else -> summary(events, inRange, range, nowMs, previousDays)
        }
    }

    // ------------------------------------------------------------------ topics

    private fun hazards(q: String, inRange: List<LadduEvent>, r: Range): AssistantAnswer {
        var list = inRange.filter { it.type.category == EventCategory.HAZARD }
        val plastic = has(q, "plastic", "wrapper")
        if (plastic) list = list.filter { it.metadata["category"] in setOf("PLASTIC", "PACKAGING") }
        if (list.isEmpty()) {
            return AssistantAnswer(
                "The records ${r.label} do not show any ${if (plastic) "plastic-related " else ""}hazard events. " +
                    "That is not proof that nothing happened: the camera only reports what it detected, it cannot see every object, " +
                    "and it cannot see the dog while it is out of view or the camera is offline.",
            )
        }
        val lines = list.take(5).joinToString("\n") { e ->
            "• ${time(e.timestamp)} ${e.type.label}: ${e.metadata["objectName"] ?: e.metadata["object"] ?: "object"} " +
                "(risk ${e.metadata["risk"] ?: "?"}, evidence ${e.metadata["evidence"] ?: "?"}). ${e.metadata["explanation"].orEmpty()}"
        }
        val more = if (list.size > 5) "\n…and ${list.size - 5} more." else ""
        val incidents = list.map { it.metadata["incidentId"] ?: it.eventId }.toSet().size
        return AssistantAnswer(
            "${r.label.replaceFirstChar { it.uppercase() }}: $incidents possible hazard situation(s), ${list.size} event(s).\n$lines$more\n" +
                "These are possible interactions seen by the camera, not confirmed. Please review the clips.",
            list.map { it.eventId }, uncertain = true,
        )
    }

    private fun barking(inRange: List<LadduEvent>, r: Range): AssistantAnswer {
        val bark = inRange.filter { it.type == EventType.BARK || it.type == EventType.REPEATED_BARK || it.type == EventType.HOWL }
        if (bark.isEmpty()) return AssistantAnswer("No barking or howling was recorded ${r.label}.")
        val day = DaySummary.of(bark, r.from, r.to)
        val times = bark.take(6).joinToString(", ") { time(it.timestamp) }
        return AssistantAnswer(
            "${bark.size} barking/howling event(s) ${r.label}, in ${day.barkEpisodes + day.howlEvents} separate period(s). Times: $times" +
                (if (bark.size > 6) ", …" else "") + ".",
            bark.map { it.eventId },
        )
    }

    private fun activity(all: List<LadduEvent>, r: Range, now: Long): AssistantAnswer {
        val day = DaySummary.of(all, r.from, minOf(r.to, now))
        val seenMin = day.presenceMs / 60_000
        val covered = day.coveredMs / 3_600_000f
        return AssistantAnswer(
            "${r.label.replaceFirstChar { it.uppercase() }}: the dog was in view for about $seenMin min with ${day.movementEvents} movement event(s), " +
                "over ${"%.1f".format(Locale.US, covered)} h the camera was online. " +
                "Time when the dog was out of view is not counted as resting or sleeping: the camera cannot tell where it was.",
        )
    }

    private fun camera(inRange: List<LadduEvent>, r: Range): AssistantAnswer {
        val offline = inRange.filter { it.type == EventType.CAMERA_ONLINE && it.durationMs > 0 }
        val low = inRange.count { it.type == EventType.LOW_BATTERY }
        if (offline.isEmpty() && low == 0) return AssistantAnswer("The camera reported no outages or low-battery warnings ${r.label}.")
        val lines = offline.joinToString("; ") { "offline ${(it.durationMs / 60_000)} min, back at ${time(it.timestamp)}" }
        return AssistantAnswer("Camera ${r.label}: ${if (offline.isEmpty()) "no outages" else lines}. Low-battery warnings: $low.")
    }

    private fun summary(all: List<LadduEvent>, inRange: List<LadduEvent>, r: Range, now: Long, previous: List<DaySummary>): AssistantAnswer {
        if (inRange.isEmpty()) {
            return AssistantAnswer("There are no recorded events ${r.label}. If the camera was monitoring, that means nothing notable was detected.")
        }
        val move = inRange.count { it.type == EventType.MOVEMENT }
        val bark = inRange.count { it.type == EventType.BARK || it.type == EventType.REPEATED_BARK || it.type == EventType.HOWL }
        val haz = inRange.filter { it.type.category == EventCategory.HAZARD }
        val sb = StringBuilder("${r.label.replaceFirstChar { it.uppercase() }}: $move movement event(s), $bark barking/howling event(s)")
        sb.append(if (haz.isEmpty()) ", no hazard events." else ", ${haz.size} possible hazard event(s) (highest risk ${haz.mapNotNull { it.metadata["risk"] }.maxByOrNull { risks.indexOf(it) } ?: "?"}).")
        val gaps = inRange.count { it.type == EventType.CAMERA_ONLINE && it.durationMs > 0 }
        if (gaps > 0) sb.append(" The camera had $gaps outage(s); nothing is known about those periods.")
        var uncertain = haz.isNotEmpty()
        if (r.wholeDay) {
            val today = DaySummary.of(all, r.from, now)
            observations(today, Baseline.from(previous)).forEach { sb.append("\n• ").append(it); uncertain = true }
        }
        return AssistantAnswer(sb.toString(), inRange.map { it.eventId }.take(20), uncertain = uncertain)
    }

    // ------------------------------------------------------------------ time

    private fun rangeFor(q: String, now: Long): Range {
        val startToday = Calendar.getInstance(zone).apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when {
            has(q, "last hour", "past hour") -> Range(now - 3_600_000, now, "in the last hour")
            has(q, "away") -> Range(now - 4 * 3_600_000, now, "in the last 4 hours")
            has(q, "yesterday") -> Range(startToday - 86_400_000, startToday - 1, "yesterday", wholeDay = true)
            has(q, "week") -> Range(now - 7 * 86_400_000L, now, "in the last 7 days")
            else -> Range(startToday, now, "today", wholeDay = true)
        }
    }

    private fun time(ms: Long) = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }.format(Date(ms))
    private fun has(q: String, vararg keys: String) = keys.any { it in q }
    private val risks = listOf("INFO", "CAUTION", "HIGH", "CRITICAL")
}
