package com.laddu.app.core.insights

import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class InsightsTest {
    private val hour = 3_600_000L
    private val day0 = 1_800_000_000_000L - (1_800_000_000_000L % 86_400_000L) // a UTC midnight
    private val utc = TimeZone.getTimeZone("UTC")
    private var n = 0

    private fun ev(type: EventType, ts: Long, dur: Long = 0, meta: Map<String, String> = emptyMap()) =
        LadduEvent("e${n++}", "cam", "owner", type, ts, dur, 0.8f, false, meta)

    private fun hazard(ts: Long, category: String = "PLASTIC", risk: String = "HIGH", incident: String = "inc1") =
        ev(EventType.POSSIBLE_CHEWING, ts, 4_000, mapOf("category" to category, "risk" to risk, "incidentId" to incident,
            "objectName" to "Bottle", "evidence" to "0.72", "explanation" to "The bottle stayed at the dog."))

    // ---------------------------------------------------------------- DaySummary
    @Test fun `known offline gaps are subtracted from covered time, unknown time is not invented`() {
        val now = day0 + 10 * hour
        val e = listOf(ev(EventType.CAMERA_ONLINE, day0 + 5 * hour, dur = 2 * hour)) // offline 03:00-05:00
        val s = DaySummary.of(e, day0, now)
        assertEquals(8 * hour, s.coveredMs)
        assertEquals(1, s.knownGaps.size)
    }

    @Test fun `overlapping gaps are merged and a gap before the day is clipped`() {
        val now = day0 + 10 * hour
        val e = listOf(
            ev(EventType.CAMERA_ONLINE, day0 + 3 * hour, dur = 5 * hour),     // started the day before: counts 00:00-03:00
            ev(EventType.CAMERA_ONLINE, day0 + 4 * hour, dur = 2 * hour),     // 02:00-04:00 overlaps the first
        )
        assertEquals(6 * hour, DaySummary.of(e, day0, now).coveredMs) // 10h - (00:00..04:00)
    }

    @Test fun `barking events within ten minutes are one episode`() {
        val e = listOf(
            ev(EventType.BARK, day0 + hour, 20_000), ev(EventType.BARK, day0 + hour + 5 * 60_000, 20_000), // one episode
            ev(EventType.BARK, day0 + 3 * hour, 10_000),                                                    // second
        )
        assertEquals(2, DaySummary.of(e, day0, day0 + 5 * hour).barkEpisodes)
    }

    @Test fun `hazard events from one incident count once`() {
        val e = listOf(hazard(day0 + hour, risk = "CAUTION"), hazard(day0 + hour + 5_000, risk = "HIGH"), hazard(day0 + 4 * hour, incident = "inc2"))
        val s = DaySummary.of(e, day0, day0 + 6 * hour)
        assertEquals(3, s.hazardEvents.size)
        assertEquals(2, s.hazardIncidents)
        assertEquals("HIGH", s.highestRisk)
    }

    // ---------------------------------------------------------------- Baseline
    private fun day(bark: Int, move: Int = 10, covered: Long = 20 * hour) =
        DaySummary(0, 0, covered, emptyList(), 0, move, bark, bark, 0, emptyList(), 0, null)

    @Test fun `no baseline with too little history`() {
        assertNull(Baseline.from(listOf(day(1), day(1))))
        assertNull(Baseline.from(listOf(day(1), day(1), day(1, covered = hour)))) // third day barely monitored
    }

    @Test fun `baseline needs three well-covered days, then flags only a clear deviation`() {
        val b = Baseline.from(listOf(day(1), day(2), day(1)))
        assertNotNull(b)
        assertTrue(observations(day(2), b).isEmpty())            // normal
        assertTrue(observations(day(9), b).single().contains("above")) // clearly more
        assertTrue(observations(day(9), null).isEmpty())          // no baseline -> no claim
    }

    @Test fun `observations are never medical statements`() {
        val b = Baseline.from(listOf(day(1), day(1), day(1)))!!
        val text = observations(day(12, move = 0), b).joinToString(" ").lowercase()
        assertFalse(listOf("sick", "ill", "pain", "diagnos", "disease").any { it in text })
    }

    // ---------------------------------------------------------------- Assistant
    private val bot = AssistantEngine(utc)
    private val now = day0 + 15 * hour

    @Test fun `asking about plastic with no records does not invent anything and says what it cannot know`() {
        val a = bot.answer("Did my dog interact with plastic?", emptyList(), now)
        assertTrue(a.text.contains("do not show"))
        assertTrue(a.text.contains("not proof"))
        assertTrue(a.eventIds.isEmpty())
    }

    @Test fun `a plastic hazard is reported as possible and linked to its event`() {
        val h = hazard(day0 + 10 * hour)
        val a = bot.answer("Did my dog interact with plastic today?", listOf(h, hazard(day0 + 11 * hour, category = "SHARP")), now)
        assertTrue(a.text.contains("not confirmed"))
        assertTrue(a.uncertain)
        assertEquals(listOf(h.eventId), a.eventIds)
    }

    @Test fun `barking answers list real times`() {
        val e = listOf(ev(EventType.BARK, day0 + 9 * hour), ev(EventType.BARK, day0 + 13 * hour + 30 * 60_000))
        val a = bot.answer("When did my dog bark?", e, now)
        assertTrue(a.text.contains("09:00"))
        assertTrue(a.text.contains("13:30"))
        assertEquals(2, a.eventIds.size)
    }

    @Test fun `no barking is said plainly`() {
        assertTrue(bot.answer("did he bark today", listOf(ev(EventType.MOVEMENT, day0 + hour)), now).text.contains("No barking"))
    }

    @Test fun `last hour only uses the last hour`() {
        val e = listOf(ev(EventType.MOVEMENT, now - 30 * 60_000), ev(EventType.MOVEMENT, now - 3 * hour))
        assertTrue(bot.answer("Summarize the last hour", e, now).text.contains("1 movement"))
    }

    @Test fun `empty history is not turned into reassurance`() {
        val t = bot.answer("What happened today?", emptyList(), now).text
        assertTrue(t.contains("no recorded events"))
    }

    @Test fun `rest and activity answers do not treat out-of-view time as sleep`() {
        val a = bot.answer("How much time did my dog spend resting?", listOf(ev(EventType.DOG_PRESENCE, day0 + hour, 30 * 60_000)), now)
        assertTrue(a.text.contains("not counted as resting"))
    }

    @Test fun `unknown questions fall back to a summary, not a made-up answer`() {
        val a = bot.answer("What is the meaning of life?", listOf(ev(EventType.MOVEMENT, day0 + hour)), now)
        assertTrue(a.text.contains("movement"))
    }
}
