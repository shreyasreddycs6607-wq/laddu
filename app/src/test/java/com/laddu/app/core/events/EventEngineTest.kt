package com.laddu.app.core.events

import com.laddu.app.core.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventEngineTest {

    private var counter = 0
    private fun engine(config: DetectionConfig = DetectionConfig()) =
        EventEngine("cam1", "owner1", config) { "e${++counter}" }

    private fun EventEngine.feed(vararg inputs: EngineInput) = inputs.flatMap { onInput(it) }

    private fun List<EngineOutput>.ofType(t: EventType, phase: EngineOutput.Phase? = null) =
        filter { it.event.type == t && (phase == null || it.phase == phase) }

    // ---------------------------------------------------------------- movement
    @Test fun `one logical movement event with correct duration, not hundreds`() {
        val e = engine()
        val out = mutableListOf<EngineOutput>()
        // dog moves continuously for 102 s, a sample every second
        for (s in 0..102) out += e.onInput(EngineInput.MovementSample(10_000L + s * 1000, confirmed = true, amount = 0.1f))
        // then stops; ticks run on
        for (s in 103..130) out += e.onInput(EngineInput.Tick(10_000L + s * 1000))

        val started = out.ofType(EventType.MOVEMENT, EngineOutput.Phase.STARTED)
        val done = out.ofType(EventType.MOVEMENT, EngineOutput.Phase.COMPLETED)
        assertEquals(1, started.size)
        assertEquals(1, done.size)
        assertEquals(started[0].event.eventId, done[0].event.eventId)
        // movement began at the first confirmed sample and lasted 102 s (1m 42s)
        assertEquals(10_000L, done[0].event.timestamp)
        assertEquals(102_000L, done[0].event.durationMs)
        assertFalse(done[0].event.ongoing)
    }

    @Test fun `single twitch does not create a movement event (debounce)`() {
        val e = engine()
        val out = e.feed(
            EngineInput.MovementSample(1_000, true),
            EngineInput.MovementSample(2_000, false),
            EngineInput.Tick(20_000),
        )
        assertTrue(out.ofType(EventType.MOVEMENT).isEmpty())
    }

    @Test fun `unconfirmed motion (not the dog) never starts an event`() {
        val e = engine()
        val out = (0..60).flatMap { e.onInput(EngineInput.MovementSample(it * 500L, confirmed = false, amount = 0.5f)) }
        assertTrue(out.isEmpty())
    }

    @Test fun `short pause inside movement stays one event, long pause ends it, next movement is a new event`() {
        val e = engine()
        val out = mutableListOf<EngineOutput>()
        for (s in 0..4) out += e.onInput(EngineInput.MovementSample(s * 1000L, true))
        for (s in 9..12) out += e.onInput(EngineInput.MovementSample(s * 1000L, true)) // 5 s gap < 10 s
        assertEquals(1, out.ofType(EventType.MOVEMENT, EngineOutput.Phase.STARTED).size)
        out += e.onInput(EngineInput.Tick(60_000)) // long idle
        assertEquals(1, out.ofType(EventType.MOVEMENT, EngineOutput.Phase.COMPLETED).size)
        for (s in 0..3) out += e.onInput(EngineInput.MovementSample(100_000L + s * 1000, true))
        assertEquals(2, out.ofType(EventType.MOVEMENT, EngineOutput.Phase.STARTED).size)
    }

    @Test fun `movement notifications are rate limited`() {
        val e = engine(DetectionConfig(movementNotifyCooldownMs = 5 * 60_000))
        val out = mutableListOf<EngineOutput>()
        fun burst(startMs: Long) {
            for (s in 0..3) out += e.onInput(EngineInput.MovementSample(startMs + s * 1000, true))
            out += e.onInput(EngineInput.Tick(startMs + 60_000))
        }
        burst(0); burst(100_000); burst(1_000_000)
        val started = out.ofType(EventType.MOVEMENT, EngineOutput.Phase.STARTED)
        assertEquals(listOf(true, false, true), started.map { it.event.notify })
    }

    // ---------------------------------------------------------------- barking
    @Test fun `3 barks within 30 s creates a bark event`() {
        val e = engine()
        val out = e.feed(
            EngineInput.BarkSample(0, 0.8f), EngineInput.BarkSample(5_000, 0.7f), EngineInput.BarkSample(12_000, 0.9f),
        )
        val started = out.ofType(EventType.BARK, EngineOutput.Phase.STARTED)
        assertEquals(1, started.size)
        assertEquals(0L, started[0].event.timestamp)
        assertTrue(started[0].event.notify)
    }

    @Test fun `2 barks are not enough and barks spread over more than 30 s do not count`() {
        val e = engine()
        val out = e.feed(EngineInput.BarkSample(0, 0.9f), EngineInput.BarkSample(20_000, 0.9f), EngineInput.BarkSample(45_000, 0.9f))
        assertTrue(out.ofType(EventType.BARK).isEmpty())
    }

    @Test fun `continuous barking is one event with total duration`() {
        val e = engine()
        val out = mutableListOf<EngineOutput>()
        for (s in 0..59) out += e.onInput(EngineInput.BarkSample(s * 1000L, 0.8f)) // 60 s non-stop
        for (s in 60..80) out += e.onInput(EngineInput.Tick(s * 1000L))
        assertEquals(1, out.ofType(EventType.BARK, EngineOutput.Phase.STARTED).size)
        val done = out.ofType(EventType.BARK, EngineOutput.Phase.COMPLETED)
        assertEquals(1, done.size)
        assertEquals(60_000L, done[0].event.durationMs) // 59 s span + 1 s sample
        assertEquals("60", done[0].event.metadata["count"])
    }

    @Test fun `repeated barking raises one higher priority event and then cools down`() {
        val e = engine(DetectionConfig(repeatedBarkCount = 3, repeatedBarkWindowMs = 5 * 60_000, repeatedBarkCooldownMs = 10 * 60_000))
        val out = mutableListOf<EngineOutput>()
        fun episode(t0: Long) {
            for (i in 0..2) out += e.onInput(EngineInput.BarkSample(t0 + i * 1000, 0.9f))
            out += e.onInput(EngineInput.Tick(t0 + 30_000))
        }
        episode(0); episode(60_000); episode(120_000); episode(180_000)
        assertEquals(4, out.ofType(EventType.BARK, EngineOutput.Phase.STARTED).size)
        assertEquals(1, out.ofType(EventType.REPEATED_BARK).size)
    }

    @Test fun `configurable threshold - 2 barks in 10 s`() {
        val e = engine(DetectionConfig(barkCount = 2, barkWindowMs = 10_000))
        val out = e.feed(EngineInput.BarkSample(0, 0.9f), EngineInput.BarkSample(4_000, 0.9f))
        assertEquals(1, out.ofType(EventType.BARK, EngineOutput.Phase.STARTED).size)
    }

    // ---------------------------------------------------------------- howl
    @Test fun `howl creates one event and notifications are limited`() {
        val e = engine()
        val out = mutableListOf<EngineOutput>()
        for (s in 0..9) out += e.onInput(EngineInput.HowlSample(s * 1000L, 0.8f))
        out += e.onInput(EngineInput.Tick(60_000))
        out += e.onInput(EngineInput.HowlSample(70_000, 0.8f)) // soon after: new event but not notifying
        val started = out.ofType(EventType.HOWL, EngineOutput.Phase.STARTED)
        assertEquals(2, started.size)
        assertEquals(listOf(true, false), started.map { it.event.notify })
    }

    // ---------------------------------------------------------------- presence
    @Test fun `first sighting is silent, return after long absence notifies`() {
        val e = engine()
        val out = mutableListOf<EngineOutput>()
        out += e.onInput(EngineInput.DogSeen(0, 0.9f))
        out += e.onInput(EngineInput.DogSeen(5_000, 0.9f))
        out += e.onInput(EngineInput.Tick(100_000)) // dog gone: presence ends
        out += e.onInput(EngineInput.DogSeen(400_000, 0.9f)) // back after 6+ minutes
        val started = out.ofType(EventType.DOG_PRESENCE, EngineOutput.Phase.STARTED)
        assertEquals(2, started.size)
        assertFalse(started[0].event.notify)
        assertTrue(started[1].event.notify)
        assertEquals("true", started[1].event.metadata["returned"])
        assertEquals(1, out.ofType(EventType.DOG_PRESENCE, EngineOutput.Phase.COMPLETED).size)
    }

    @Test fun `brief disappearance is not a return`() {
        val e = engine()
        val out = mutableListOf<EngineOutput>()
        out += e.onInput(EngineInput.DogSeen(0, 0.9f))
        out += e.onInput(EngineInput.DogSeen(60_000, 0.9f)) // 60 s gap, but only 45 s absent timeout...
        val started = out.ofType(EventType.DOG_PRESENCE, EngineOutput.Phase.STARTED)
        assertEquals(2, started.size)
        assertFalse(started[1].event.notify) // < 2 min absence: no "returned" notification
    }

    // ---------------------------------------------------------------- system
    @Test fun `offline is kept locally and online is reported with offline duration`() {
        val e = engine()
        val off = e.onInput(EngineInput.NetworkChanged(1_000, online = false))
        val on = e.onInput(EngineInput.NetworkChanged(61_000, online = true))
        assertEquals(EventType.CAMERA_OFFLINE, off.single().event.type)
        assertEquals("true", off.single().event.metadata[EventEngine.SOURCE_LOCAL])
        assertFalse(off.single().event.notify)
        assertEquals(EventType.CAMERA_ONLINE, on.single().event.type)
        assertEquals(60_000L, on.single().event.durationMs)
        assertTrue(on.single().event.notify)
    }

    @Test fun `no duplicate network events when state does not change`() {
        val e = engine()
        assertTrue(e.onInput(EngineInput.NetworkChanged(0, online = true)).isEmpty())
    }

    @Test fun `low battery fires once until recharged`() {
        val e = engine()
        assertTrue(e.onInput(EngineInput.BatteryChanged(0, 40, false)).isEmpty())
        assertEquals(1, e.onInput(EngineInput.BatteryChanged(1, 14, false)).size)
        assertTrue(e.onInput(EngineInput.BatteryChanged(2, 12, false)).isEmpty())
        e.onInput(EngineInput.BatteryChanged(3, 50, true)) // charged: re-arm
        assertEquals(1, e.onInput(EngineInput.BatteryChanged(4, 10, false)).size)
    }

    @Test fun `charging low battery is not an alert`() {
        val e = engine()
        assertTrue(e.onInput(EngineInput.BatteryChanged(0, 5, true)).isEmpty())
    }

    // ---------------------------------------------------------------- status / flush
    @Test fun `status reflects live detections and flush completes running events`() {
        val e = engine()
        e.onInput(EngineInput.DogSeen(0, 0.9f))
        for (s in 0..3) e.onInput(EngineInput.MovementSample(s * 1000L, true))
        val st = e.status(3_000)
        assertTrue(st.dogPresent); assertTrue(st.moving); assertFalse(st.barking)
        val flushed = e.flush(4_000)
        assertEquals(setOf(EventType.DOG_PRESENCE, EventType.MOVEMENT), flushed.map { it.event.type }.toSet())
        assertTrue(flushed.all { !it.event.ongoing && it.phase == EngineOutput.Phase.COMPLETED })
        assertFalse(e.status(4_000).dogPresent)
    }

    @Test fun `every event carries camera and owner ids and a unique id`() {
        val e = engine()
        val out = e.feed(EngineInput.DogSeen(0, 0.9f), EngineInput.NetworkChanged(1, false))
        out.forEach { assertEquals("cam1", it.event.cameraId); assertEquals("owner1", it.event.ownerId) }
        assertEquals(out.size, out.map { it.event.eventId }.toSet().size)
        assertNotNull(out.first().event.eventId)
        assertNull(out.first().event.snapshotRef)
    }
}
