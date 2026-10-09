package com.laddu.app.core.notifications

import com.laddu.app.core.events.EngineInput
import com.laddu.app.core.events.EventEngine
import com.laddu.app.core.insights.ActivityClassifier
import com.laddu.app.core.insights.DogActivity
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.NotificationPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QuietHoursAndActivityTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(hour: Int, minute: Int = 0) = Calendar.getInstance(utc).apply {
        timeInMillis = 0; set(2026, Calendar.OCTOBER, 10, hour, minute, 0)
    }.timeInMillis

    private val night = NotificationPrefs(quietHours = true, quietStartMin = 22 * 60, quietEndMin = 7 * 60)

    // ---------------------------------------------------------------- quiet hours
    @Test fun `a window that wraps midnight is quiet at night and not by day`() {
        assertTrue(QuietHours.isQuiet(night, at(23), utc))
        assertTrue(QuietHours.isQuiet(night, at(3), utc))
        assertTrue(QuietHours.isQuiet(night, at(6, 59), utc))
        assertFalse(QuietHours.isQuiet(night, at(7), utc))
        assertFalse(QuietHours.isQuiet(night, at(12), utc))
        assertTrue(QuietHours.isQuiet(night, at(22), utc))
        assertFalse(QuietHours.isQuiet(night, at(21, 59), utc))
    }

    @Test fun `a same-day window and a disabled or empty window`() {
        val lunch = NotificationPrefs(quietHours = true, quietStartMin = 12 * 60, quietEndMin = 14 * 60)
        assertTrue(QuietHours.isQuiet(lunch, at(13), utc))
        assertFalse(QuietHours.isQuiet(lunch, at(15), utc))
        assertFalse(QuietHours.isQuiet(night.copy(quietHours = false), at(23), utc))
        assertFalse(QuietHours.isQuiet(night.copy(quietStartMin = 300, quietEndMin = 300), at(5), utc))
    }

    private fun payload(type: EventType, risk: String? = null) =
        AlertPayload("e", "cam", type, "t", "b", "Cam", 0, risk)

    @Test fun `quiet hours silence ordinary alerts and low-risk hazards but not high-risk ones`() {
        val policy = NotificationPolicy()
        // the policy uses the device zone; build a "now" that is night in that zone
        val zone = TimeZone.getDefault()
        val nightNow = Calendar.getInstance(zone).apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 0) }.timeInMillis
        assertFalse(policy.shouldShow(payload(EventType.BARK), night, nightNow))
        assertFalse(policy.shouldShow(payload(EventType.POSSIBLE_HAZARD_INTERACTION, "CAUTION"), night, nightNow))
        assertTrue(policy.shouldShow(payload(EventType.POSSIBLE_CHEWING, "HIGH"), night, nightNow))
        assertTrue(policy.shouldShow(payload(EventType.POSSIBLE_INGESTION, "CRITICAL"), night, nightNow + 60_000))
    }

    @Test fun `quiet hours are off by default and survive JSON`() {
        assertFalse(NotificationPrefs().quietHours)
        val back = NotificationPrefs.fromJson(night.toJson())
        assertTrue(back.quietHours); assertEquals(22 * 60, back.quietStartMin); assertEquals(7 * 60, back.quietEndMin)
    }

    @Test fun `risk is read from the push data and unknown data is ignored`() {
        val p = AlertPayload.fromData(mapOf("type" to "POSSIBLE_CHEWING", "cameraId" to "c", "risk" to "HIGH"))!!
        assertTrue(p.urgent)
        assertFalse(AlertPayload.fromData(mapOf("type" to "BARK", "cameraId" to "c", "risk" to "HIGH"))!!.urgent) // not a hazard
    }

    // ---------------------------------------------------------------- activity
    @Test fun `activity thresholds`() {
        assertEquals(DogActivity.OUT_OF_VIEW, ActivityClassifier.classify(false, 0.5f))
        assertEquals(DogActivity.RESTING, ActivityClassifier.classify(true, 0.01f))
        assertEquals(DogActivity.WALKING, ActivityClassifier.classify(true, 0.10f))
        assertEquals(DogActivity.RUNNING, ActivityClassifier.classify(true, 0.30f))
        assertEquals(DogActivity.OUT_OF_VIEW, ActivityClassifier.parse("nonsense"))
    }

    @Test fun `engine reports activity from recent movement and never keeps a stale value`() {
        val e = EventEngine("cam", "owner")
        e.onInput(EngineInput.DogSeen(0, 0.9f))
        e.onInput(EngineInput.MovementSample(1_000, true, 0.30f))
        assertEquals(DogActivity.RUNNING, e.status(1_500).activity)
        assertEquals(DogActivity.RESTING, e.status(10_000).activity) // sample is old: no recent movement
        assertEquals(DogActivity.OUT_OF_VIEW, EventEngine("cam", "owner").status(0).activity)
    }
}
