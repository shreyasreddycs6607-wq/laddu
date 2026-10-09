package com.laddu.app.core.insights

import com.laddu.app.core.model.EventCategory
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import kotlin.math.max
import kotlin.math.min

/** One day of what the camera actually recorded. Everything here is counted from stored events, never guessed. */
data class DaySummary(
    val dayStartMs: Long,
    val dayEndMs: Long,
    /** Time covered by monitoring: the day (so far) minus *known* offline gaps. Unknown gaps cannot be subtracted. */
    val coveredMs: Long,
    val knownGaps: List<LongRange>,
    val presenceMs: Long,
    val movementEvents: Int,
    val barkEvents: Int,
    /** Barking events grouped into episodes (events less than [EPISODE_GAP_MS] apart are one episode). */
    val barkEpisodes: Int,
    val howlEvents: Int,
    val hazardEvents: List<LadduEvent>,
    val hazardIncidents: Int,
    val highestRisk: String?,
) {
    val eventCount: Int get() = movementEvents + barkEvents + howlEvents + hazardEvents.size

    companion object {
        const val EPISODE_GAP_MS = 10 * 60_000L
        private const val DAY_MS = 24L * 3600_000

        fun of(events: List<LadduEvent>, dayStartMs: Long, nowMs: Long): DaySummary {
            val dayEnd = min(dayStartMs + DAY_MS, nowMs)
            val inDay = events.filter { it.timestamp in dayStartMs until dayStartMs + DAY_MS }

            // "Camera online" carries how long it had been offline, which is the only gap record we have.
            val gaps = events.filter { it.type == EventType.CAMERA_ONLINE && it.durationMs > 0 }
                .map { (it.timestamp - it.durationMs)..it.timestamp }
                .mapNotNull { clip(it, dayStartMs, dayEnd) }
            val merged = merge(gaps)
            val covered = (dayEnd - dayStartMs).coerceAtLeast(0) - merged.sumOf { it.last - it.first }

            val bark = inDay.filter { it.type == EventType.BARK || it.type == EventType.REPEATED_BARK }.sortedBy { it.timestamp }
            var episodes = 0
            var lastEnd: Long? = null
            for (b in bark) {
                if (lastEnd == null || b.timestamp - lastEnd > EPISODE_GAP_MS) episodes++
                lastEnd = max(lastEnd ?: 0L, b.timestamp + b.durationMs)
            }
            val hazards = inDay.filter { it.type.category == EventCategory.HAZARD }.sortedBy { it.timestamp }
            val order = listOf("INFO", "CAUTION", "HIGH", "CRITICAL")
            return DaySummary(
                dayStartMs = dayStartMs, dayEndMs = dayEnd, coveredMs = covered.coerceAtLeast(0), knownGaps = merged,
                presenceMs = inDay.filter { it.type == EventType.DOG_PRESENCE }.sumOf { it.durationMs },
                movementEvents = inDay.count { it.type == EventType.MOVEMENT },
                barkEvents = bark.size, barkEpisodes = episodes,
                howlEvents = inDay.count { it.type == EventType.HOWL },
                hazardEvents = hazards,
                hazardIncidents = hazards.map { it.metadata["incidentId"] ?: it.eventId }.toSet().size,
                highestRisk = hazards.mapNotNull { it.metadata["risk"] }.maxByOrNull { order.indexOf(it) },
            )
        }

        private fun clip(r: LongRange, from: Long, to: Long): LongRange? {
            val a = max(r.first, from); val b = min(r.last, to)
            return if (b > a) a..b else null
        }

        private fun merge(ranges: List<LongRange>): List<LongRange> {
            val out = ArrayList<LongRange>()
            for (r in ranges.sortedBy { it.first }) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last.last) out[out.lastIndex] = last.first..max(last.last, r.last) else out += r
            }
            return out
        }
    }
}

/**
 * What is normal for this dog, from its own recent days. Needs real history: with fewer than [MIN_DAYS] days of
 * decent coverage it says nothing rather than inventing a baseline.
 */
data class Baseline(val days: Int, val barkEpisodes: Float, val movementEvents: Float, val presenceMs: Float) {
    companion object {
        const val MIN_DAYS = 3
        const val MIN_COVERED_MS = 6L * 3600_000

        fun from(previous: List<DaySummary>): Baseline? {
            val good = previous.filter { it.coveredMs >= MIN_COVERED_MS }
            if (good.size < MIN_DAYS) return null
            return Baseline(
                good.size,
                good.map { it.barkEpisodes }.average().toFloat(),
                good.map { it.movementEvents }.average().toFloat(),
                good.map { it.presenceMs }.average().toFloat(),
            )
        }
    }
}

/** Observations (not diagnoses) where today differs clearly from the dog's own baseline. Empty when there is no baseline. */
fun observations(today: DaySummary, baseline: Baseline?): List<String> {
    if (baseline == null || today.coveredMs < Baseline.MIN_COVERED_MS / 2) return emptyList()
    val out = ArrayList<String>()
    if (today.barkEpisodes >= baseline.barkEpisodes * 2 + 2) {
        out += "Barking episodes (${today.barkEpisodes}) were well above this dog's recent average (${"%.1f".format(baseline.barkEpisodes)})."
    }
    if (baseline.movementEvents >= 5 && today.movementEvents <= baseline.movementEvents * 0.3f) {
        out += "Movement events (${today.movementEvents}) were much lower than the recent average (${"%.0f".format(baseline.movementEvents)}), " +
            "in the time the camera was watching. That can simply mean the dog was out of view."
    }
    return out
}
