package com.laddu.app.features.activity

import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import java.util.Calendar
import java.util.TimeZone

data class DayStats(
    val dayStartMs: Long,
    val presenceMs: Long = 0,
    val movementEvents: Int = 0,
    val barkEvents: Int = 0,
    val howlEvents: Int = 0,
    val movementMs: Long = 0,
    val barkMs: Long = 0,
)

object ActivityStats {
    fun startOfDay(ms: Long, tz: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(tz).apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun addDays(dayStart: Long, n: Int, tz: TimeZone) =
        Calendar.getInstance(tz).apply { timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, n) }.timeInMillis

    /**
     * One entry per day for the last [days] days (oldest first, today last). Ongoing events are
     * measured up to [nowMs]. REPEATED_BARK is not counted again (it summarises BARK events).
     */
    fun compute(events: List<LadduEvent>, nowMs: Long, days: Int, tz: TimeZone = TimeZone.getDefault()): List<DayStats> {
        val today = startOfDay(nowMs, tz)
        val starts = (days - 1 downTo 0).map { addDays(today, -it, tz) }
        val byDay = starts.associateWith { DayStats(it) }.toMutableMap()
        for (e in events) {
            val d = startOfDay(e.timestamp, tz)
            val cur = byDay[d] ?: continue
            val dur = if (e.ongoing) (nowMs - e.timestamp).coerceAtLeast(0) else e.durationMs
            byDay[d] = when (e.type) {
                EventType.DOG_PRESENCE -> cur.copy(presenceMs = cur.presenceMs + dur)
                EventType.MOVEMENT -> cur.copy(movementEvents = cur.movementEvents + 1, movementMs = cur.movementMs + dur)
                EventType.BARK -> cur.copy(barkEvents = cur.barkEvents + 1, barkMs = cur.barkMs + dur)
                EventType.HOWL -> cur.copy(howlEvents = cur.howlEvents + 1)
                else -> cur
            }
        }
        return starts.map { byDay.getValue(it) }
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }
}
