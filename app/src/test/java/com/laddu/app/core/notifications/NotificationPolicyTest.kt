package com.laddu.app.core.notifications

import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.NotificationPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    private fun payload(type: EventType, cam: String = "cam1") =
        AlertPayload("e", cam, type, "t", "b", "Laddu", 0)

    @Test fun `similar alerts are rate limited per camera and type`() {
        val p = NotificationPolicy(); val prefs = NotificationPrefs(cooldownSec = 120)
        assertTrue(p.shouldShow(payload(EventType.BARK), prefs, 0))
        assertFalse(p.shouldShow(payload(EventType.BARK), prefs, 60_000))   // within cooldown
        assertTrue(p.shouldShow(payload(EventType.BARK), prefs, 121_000))   // after cooldown
    }

    @Test fun `different types and different cameras are independent`() {
        val p = NotificationPolicy(); val prefs = NotificationPrefs(cooldownSec = 300)
        assertTrue(p.shouldShow(payload(EventType.BARK), prefs, 0))
        assertTrue(p.shouldShow(payload(EventType.MOVEMENT), prefs, 1))
        assertTrue(p.shouldShow(payload(EventType.BARK, cam = "cam2"), prefs, 2))
    }

    @Test fun `muted categories never notify and do not consume the cooldown`() {
        val p = NotificationPolicy()
        val muted = NotificationPrefs(barking = false)
        assertFalse(p.shouldShow(payload(EventType.BARK), muted, 0))
        assertTrue(p.shouldShow(payload(EventType.BARK), NotificationPrefs(), 1)) // user re-enabled
    }

    @Test fun `repeated barking and low battery have long fixed cooldowns`() {
        val p = NotificationPolicy(); val prefs = NotificationPrefs(cooldownSec = 0)
        assertEquals(10 * 60_000L, p.cooldownMs(EventType.REPEATED_BARK, prefs))
        assertEquals(30 * 60_000L, p.cooldownMs(EventType.LOW_BATTERY, prefs))
        assertTrue(p.shouldShow(payload(EventType.LOW_BATTERY), prefs, 0))
        assertFalse(p.shouldShow(payload(EventType.LOW_BATTERY), prefs, 5 * 60_000))
    }

    @Test fun `howl uses barking switch, dog returned uses its own`() {
        val prefs = NotificationPrefs(barking = false, dogReturned = true)
        assertFalse(prefs.allows(EventType.HOWL)); assertFalse(prefs.allows(EventType.BARK))
        assertTrue(prefs.allows(EventType.DOG_PRESENCE))
    }

    @Test fun `fcm data message is parsed, bad messages are ignored`() {
        val ok = AlertPayload.fromData(mapOf("type" to "BARK", "cameraId" to "c", "eventId" to "e1", "body" to "Your dog has been barking for 8 seconds.", "title" to "T", "timestamp" to "123"))
        assertNotNull(ok); assertEquals(EventType.BARK, ok!!.type); assertEquals(123L, ok.timestampMs)
        assertNull(AlertPayload.fromData(mapOf("type" to "NOPE", "cameraId" to "c")))
        assertNull(AlertPayload.fromData(mapOf("type" to "BARK"))) // no camera
        assertTrue(AlertPayload.defaultTitle(EventType.BARK).contains("barking"))
    }
}
