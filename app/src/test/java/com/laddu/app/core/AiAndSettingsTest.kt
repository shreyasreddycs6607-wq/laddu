package com.laddu.app.core

import com.laddu.app.core.ai.BoundingBox
import com.laddu.app.core.ai.Detection
import com.laddu.app.core.ai.DogTracker
import com.laddu.app.core.ai.MotionDetector
import com.laddu.app.core.ai.confidenceThreshold
import com.laddu.app.core.ai.profileFor
import com.laddu.app.core.audio.AudioWindower
import com.laddu.app.core.audio.SoundClass
import com.laddu.app.core.audio.barkThreshold
import com.laddu.app.core.audio.resampleToMono
import com.laddu.app.core.audio.soundClassOf
import com.laddu.app.core.device.computeThermal
import com.laddu.app.core.events.DetectionConfig
import com.laddu.app.core.firebase.AuthState
import com.laddu.app.core.model.AiPerformanceMode
import com.laddu.app.core.model.AppMode
import com.laddu.app.core.model.CameraInfo
import com.laddu.app.core.model.CameraSettings
import com.laddu.app.core.model.CameraStatus
import com.laddu.app.core.model.NotificationPrefs
import com.laddu.app.core.model.RecordingSettings
import com.laddu.app.core.model.StreamQuality
import com.laddu.app.core.model.ThermalLevel
import com.laddu.app.core.model.UserProfile
import com.laddu.app.core.ui.navigation.Routes
import com.laddu.app.core.ui.navigation.resolveRoute
import com.laddu.app.features.activity.ActivityStats
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AiAndSettingsTest {

    // ------------------------------------------------------------ AI profiles / thresholds
    @Test fun `ai performance modes trade speed for battery`() {
        val low = profileFor(AiPerformanceMode.LOW, ThermalLevel.NORMAL)
        val bal = profileFor(AiPerformanceMode.BALANCED, ThermalLevel.NORMAL)
        val high = profileFor(AiPerformanceMode.HIGH, ThermalLevel.NORMAL)
        assertTrue(low.intervalMs > bal.intervalMs && bal.intervalMs > high.intervalMs)
        assertTrue(low.threads <= bal.threads && bal.threads <= high.threads)
    }

    @Test fun `overheating reduces inference rate and never raises it`() {
        for (mode in AiPerformanceMode.entries) {
            val n = profileFor(mode, ThermalLevel.NORMAL); val w = profileFor(mode, ThermalLevel.WARM); val h = profileFor(mode, ThermalLevel.HOT)
            assertTrue(w.intervalMs > n.intervalMs); assertTrue(h.intervalMs > w.intervalMs)
            assertEquals(1, h.threads); assertTrue(w.threads <= n.threads)
        }
    }

    @Test fun `thermal level combines os status and battery temperature`() {
        assertEquals(ThermalLevel.NORMAL, computeThermal(30f, 0))
        assertEquals(ThermalLevel.WARM, computeThermal(42f, 0))
        assertEquals(ThermalLevel.HOT, computeThermal(47f, 0))
        assertEquals(ThermalLevel.WARM, computeThermal(30f, 2))
        assertEquals(ThermalLevel.HOT, computeThermal(30f, 3))
        assertEquals(ThermalLevel.HOT, computeThermal(null, 5))
        assertEquals(ThermalLevel.NORMAL, computeThermal(null, 1))
    }

    @Test fun `sensitivity maps to confidence thresholds monotonically`() {
        assertTrue(confidenceThreshold(1f) < confidenceThreshold(0.5f) && confidenceThreshold(0.5f) < confidenceThreshold(0f))
        assertTrue(barkThreshold(1f) < barkThreshold(0f))
        assertEquals(0.5f, confidenceThreshold(0.5f), 0.001f)
    }

    // ------------------------------------------------------------ audio
    @Test fun `audioset labels map to laddu sound classes`() {
        assertEquals(SoundClass.BARK, soundClassOf("Bark")); assertEquals(SoundClass.BARK, soundClassOf("Bow-wow"))
        assertEquals(SoundClass.HOWL, soundClassOf("Howl")); assertEquals(SoundClass.WHINE, soundClassOf("Whimper (dog)"))
        assertEquals(SoundClass.HUMAN_VOICE, soundClassOf("Speech")); assertEquals(SoundClass.HUMAN_VOICE, soundClassOf("Conversation"))
        assertEquals(SoundClass.BACKGROUND_NOISE, soundClassOf("Silence")); assertEquals(SoundClass.UNKNOWN, soundClassOf("Guitar"))
    }

    @Test fun `windower emits exact windows from arbitrary chunks without losing samples`() {
        val got = mutableListOf<ShortArray>()
        val w = AudioWindower({ 10 }) { got += it }
        w.push(ShortArray(4) { it.toShort() })
        w.push(ShortArray(13) { (it + 4).toShort() })
        w.push(ShortArray(3) { (it + 17).toShort() })
        assertEquals(2, got.size)
        assertEquals((0..9).map { it.toShort() }, got[0].toList())
        assertEquals((10..19).map { it.toShort() }, got[1].toList())
    }

    @Test fun `resampling 48k stereo to 16k mono`() {
        val frames = 4800 // 100 ms
        val bytes = ByteArray(frames * 2 * 2)
        for (i in 0 until frames) { // constant 1000 in both channels
            val s = 1000
            bytes[i * 4] = (s and 0xFF).toByte(); bytes[i * 4 + 1] = (s shr 8).toByte()
            bytes[i * 4 + 2] = (s and 0xFF).toByte(); bytes[i * 4 + 3] = (s shr 8).toByte()
        }
        val out = resampleToMono(bytes, 48_000, 2, 16_000)
        assertEquals(1600, out.size)
        assertTrue(out.all { it.toInt() == 1000 })
    }

    // ------------------------------------------------------------ vision
    @Test fun `iou and box helpers`() {
        val a = BoundingBox(0f, 0f, 0.5f, 0.5f); val b = BoundingBox(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(0.0625f / 0.4375f, a.iou(b), 0.001f)
        assertTrue(a.intersects(b)); assertFalse(a.intersects(BoundingBox(0.6f, 0.6f, 0.9f, 0.9f)))
        assertEquals(1f, a.iou(a), 0.0001f)
    }

    private fun det(l: Float, t: Float, r: Float, b: Float) = Detection("dog", 0.9f, BoundingBox(l, t, r, b), 0)

    @Test fun `tracker keeps identity and measures dog movement vs a sleeping dog`() {
        val tracker = DogTracker()
        var track = tracker.update(listOf(det(0.30f, 0.30f, 0.50f, 0.50f)), 0).single()
        val id = track.id
        // sleeping: tiny detector jitter
        for (i in 1..4) track = tracker.update(listOf(det(0.30f + i * 0.001f, 0.30f, 0.50f + i * 0.001f, 0.50f)), i * 700L).single()
        assertEquals(id, track.id)
        assertTrue(DogTracker.motionOf(track) < 0.03f)
        // walking: box moves 20% of the frame
        for (i in 5..8) track = tracker.update(listOf(det(0.30f + (i - 4) * 0.05f, 0.30f, 0.50f + (i - 4) * 0.05f, 0.50f)), i * 300L + 2800).single()
        assertEquals(id, track.id)
        assertTrue(DogTracker.motionOf(track) >= 0.03f)
    }

    @Test fun `track expires when the dog leaves`() {
        val tracker = DogTracker()
        tracker.update(listOf(det(0.1f, 0.1f, 0.3f, 0.3f)), 0)
        tracker.update(emptyList(), 10_000)
        assertTrue(tracker.active.isEmpty())
    }

    @Test fun `motion box is rotated into upright coordinates`() {
        val b = BoundingBox(0f, 0f, 0.25f, 0.5f)
        assertEquals(b, MotionDetector.rotateUpright(b, 0))
        val r90 = MotionDetector.rotateUpright(b, 90)
        assertEquals(0.5f, r90.left, 0.001f); assertEquals(1f, r90.right, 0.001f)
        assertEquals(0f, r90.top, 0.001f); assertEquals(0.25f, r90.bottom, 0.001f)
        val r180 = MotionDetector.rotateUpright(b, 180)
        assertEquals(0.75f, r180.left, 0.001f); assertEquals(1f, r180.right, 0.001f)
        assertEquals(b, MotionDetector.rotateUpright(MotionDetector.rotateUpright(b, 90), 270))
    }

    // ------------------------------------------------------------ settings
    @Test fun `camera settings survive a json round trip and clamp bad values`() {
        val s = CameraSettings(dogSensitivity = 0.8f, barkCount = 5, aiMode = AiPerformanceMode.HIGH, howlDetection = false)
        assertEquals(s.copy(updatedAtMs = 0), CameraSettings.fromJson(s.toJson()))
        val bad = CameraSettings.fromJson(org.json.JSONObject("""{"barkCount":999,"dogSensitivity":9,"aiMode":"NOPE"}"""))
        assertEquals(20, bad.barkCount); assertEquals(1f, bad.dogSensitivity, 0f); assertEquals(AiPerformanceMode.BALANCED, bad.aiMode)
        assertEquals(CameraSettings(), CameraSettings.fromJson(null))
    }

    @Test fun `detection config follows bark settings`() {
        val c = DetectionConfig.from(CameraSettings(barkCount = 4, barkWindowSec = 20))
        assertEquals(4, c.barkCount); assertEquals(20_000L, c.barkWindowMs)
    }

    @Test fun `notification and recording prefs round trip`() {
        val n = NotificationPrefs(barking = false, cooldownSec = 300)
        assertEquals(n, NotificationPrefs.fromJson(n.toJson()))
        val r = RecordingSettings(uploadToCloud = true, quotaMb = 200)
        assertEquals(r, RecordingSettings.fromJson(r.toJson()))
    }

    @Test fun `stream quality capping`() {
        assertEquals(StreamQuality.LOW, StreamQuality.HIGH.capped(StreamQuality.LOW))
        assertEquals(StreamQuality.LOW, StreamQuality.LOW.capped(StreamQuality.HIGH))
    }

    @Test fun `camera is online only with a fresh heartbeat while monitoring`() {
        val on = CameraInfo("c", "o", "n", lastSeenMs = 100_000, status = CameraStatus(monitoring = true))
        assertTrue(on.isOnline(100_000 + 89_000)); assertFalse(on.isOnline(100_000 + 91_000))
        assertFalse(on.copy(status = CameraStatus(monitoring = false)).isOnline(100_001))
    }

    // ------------------------------------------------------------ routing
    @Test fun `router decisions`() {
        val user = AuthState.SignedIn(UserProfile("u", "a@b.c", "A"))
        assertEquals(Routes.WELCOME, resolveRoute(null, AuthState.SignedOut, false))
        assertNull(resolveRoute(AppMode.CAMERA, AuthState.Unknown, false))
        assertEquals(Routes.LOGIN, resolveRoute(AppMode.VIEWER, AuthState.SignedOut, false))
        assertEquals(Routes.CAMERA_HOME, resolveRoute(AppMode.CAMERA, user, false))
        assertEquals(Routes.VIEWER, resolveRoute(AppMode.VIEWER, user, false))
        assertEquals(Routes.FIREBASE_SETUP, resolveRoute(AppMode.VIEWER, AuthState.NotConfigured, true))
        assertEquals(Routes.FIREBASE_SETUP, resolveRoute(AppMode.CAMERA, AuthState.NotConfigured, false))
        assertEquals(Routes.CAMERA_HOME, resolveRoute(AppMode.CAMERA, AuthState.NotConfigured, true))
    }

    // ------------------------------------------------------------ activity analytics
    @Test fun `daily statistics bucket events by day and ignore repeated-bark summaries`() {
        val tz = TimeZone.getTimeZone("UTC")
        val day = 86_400_000L
        val now = 10 * day + 12 * 3_600_000L
        fun ev(t: EventType, ts: Long, d: Long = 0, ongoing: Boolean = false) = LadduEvent("e$ts$t", "c", "o", t, ts, d, ongoing = ongoing)
        val events = listOf(
            ev(EventType.BARK, 10 * day + 1000), ev(EventType.BARK, 10 * day + 2000), ev(EventType.REPEATED_BARK, 10 * day + 3000),
            ev(EventType.MOVEMENT, 9 * day + 5000, 60_000), ev(EventType.DOG_PRESENCE, 10 * day + 100, 3_600_000),
            ev(EventType.DOG_PRESENCE, 10 * day + 11 * 3_600_000L, 0, ongoing = true), // ongoing: counted up to now
            ev(EventType.HOWL, 4 * day), // outside the 3-day window
        )
        val stats = ActivityStats.compute(events, now, 3, tz)
        assertEquals(3, stats.size)
        assertEquals(2, stats[2].barkEvents); assertEquals(0, stats[2].movementEvents)
        assertEquals(1, stats[1].movementEvents); assertEquals(60_000L, stats[1].movementMs)
        assertEquals(3_600_000L + 3_600_000L, stats[2].presenceMs)
        assertEquals(0, stats.sumOf { it.howlEvents })
        assertEquals("1m 42s", ActivityStats.formatDuration(102_000)); assertEquals("5s", ActivityStats.formatDuration(5_000))
        assertEquals("2h 5m", ActivityStats.formatDuration(2 * 3_600_000L + 5 * 60_000L))
    }
}
