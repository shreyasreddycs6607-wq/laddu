package com.laddu.app.core.safety

import com.laddu.app.core.ai.BoundingBox
import com.laddu.app.core.ai.Detection
import com.laddu.app.core.ai.DogTracker
import com.laddu.app.core.events.EngineOutput
import com.laddu.app.core.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic frame sequences (no real footage): they prove the RULES - what counts as a hazard, what must stay quiet,
 * and that uncertainty is expressed - not detector accuracy on a real dog. See docs/HAZARD_DETECTION.md.
 */
class HazardEngineTest {

    private class Sim(policy: SafetyPolicy = SafetyPolicy()) {
        private var n = 0
        val tracker = DogTracker()
        val engine = HazardEngine("cam", "owner", policy, idGenerator = { "id${n++}" })
        val out = ArrayList<EngineOutput>()

        fun step(t: Long, dog: BoundingBox?, objects: List<Detection>) {
            val tracks = tracker.update(listOfNotNull(dog?.let { Detection("dog", 0.9f, it, t) }), t)
            out += engine.onFrame(tracks, objects, t)
        }

        fun run(fromMs: Long, toMs: Long, stepMs: Long = 500, dog: (Long) -> BoundingBox?, objects: (Long) -> List<Detection>): Long {
            var t = fromMs
            while (t <= toMs) { step(t, dog(t), objects(t)); t += stepMs }
            return t
        }

        val started get() = out.filter { it.phase == EngineOutput.Phase.STARTED }.map { it.event }
        val notifying get() = started.filter { it.notify }
        fun types() = started.map { it.type }.toSet()
    }

    private fun obj(label: String, box: BoundingBox, t: Long, conf: Float = 0.8f) = Detection(label, conf, box, t)

    // dog box 0.4 x 0.5 in the middle; a small object lying inside its upper half
    private val dogStill = BoundingBox(0.30f, 0.30f, 0.70f, 0.80f)
    private val onDog = BoundingBox(0.46f, 0.40f, 0.52f, 0.46f)
    private fun farObject(label: String, t: Long) = listOf(obj(label, BoundingBox(0.05f, 0.80f, 0.11f, 0.86f), t))
    private fun atDog(label: String, t: Long, dx: Float = 0f, dy: Float = 0f) =
        listOf(obj(label, BoundingBox(onDog.left + dx, onDog.top + dy, onDog.right + dx, onDog.bottom + dy), t))

    @Test fun `A - a dog walking past a bottle is never reported as chewing or ingestion`() {
        val s = Sim()
        s.run(0, 9_000, dog = { t -> val x = t / 1000f * 0.12f - 0.2f; BoundingBox(x, 0.30f, x + 0.35f, 0.80f) }, objects = { t -> listOf(obj("bottle", BoundingBox(0.50f, 0.60f, 0.56f, 0.66f), t)) })
        assertFalse(s.types().any { it in setOf(EventType.POSSIBLE_CHEWING, EventType.POSSIBLE_INGESTION, EventType.HIGH_RISK_OBJECT_INTERACTION) })
    }

    @Test fun `B - sniffing a bottle is a caution at most, never chewing`() {
        val s = Sim()
        s.run(0, 3_000, dog = { dogStill }, objects = { t -> atDog("bottle", t) })
        assertTrue(s.types().contains(EventType.POSSIBLE_HAZARD_INTERACTION))
        assertEquals("CAUTION", s.started.first().metadata["risk"])
        assertFalse(s.types().contains(EventType.POSSIBLE_CHEWING))
        assertFalse(s.types().contains(EventType.POSSIBLE_INGESTION))
    }

    @Test fun `C - a wrapper that is still, then moves with the dog, is a high-priority pickup`() {
        val s = Sim()
        val t1 = s.run(0, 2_500, dog = { BoundingBox(0.0f, 0.30f, 0.4f, 0.80f) }, objects = { t -> listOf(obj("handbag", BoundingBox(0.46f, 0.60f, 0.52f, 0.66f), t)) }) // lying still, dog away
        s.run(t1, t1 + 5_000, dog = { t -> val x = 0.2f + (t - t1) / 1000f * 0.04f; BoundingBox(x, 0.30f, x + 0.40f, 0.80f) },
            objects = { t -> val x = 0.2f + (t - t1) / 1000f * 0.04f; listOf(obj("handbag", BoundingBox(x + 0.18f, 0.45f, x + 0.24f, 0.51f), t)) })
        val high = s.started.filter { it.metadata["risk"] in setOf("HIGH", "CRITICAL") }
        assertTrue("expected a HIGH alert, got ${s.started.map { it.type to it.metadata["risk"] }}", high.isNotEmpty())
        assertTrue(high.all { it.notify })
    }

    @Test fun `D - an unknown-to-the-owner object kept at the dog and shifting is possible chewing`() {
        val s = Sim()
        s.run(0, 8_000, dog = { dogStill }, objects = { t -> atDog("bottle", t, dx = if ((t / 500) % 2 == 0L) 0.02f else -0.02f) })
        assertTrue(s.types().contains(EventType.POSSIBLE_CHEWING))
        val chew = s.started.first { it.type == EventType.POSSIBLE_CHEWING }
        assertTrue(chew.metadata.getValue("explanation").contains("chewing-like"))
    }

    @Test fun `E - eating approved food raises no hazard`() {
        val approved = SafetyPolicy(items = DefaultSafety.items.map { if ("banana" in it.labels) it.copy(approval = Approval.APPROVED) else it })
        val s = Sim(approved)
        s.run(0, 8_000, dog = { dogStill }, objects = { t -> atDog("banana", t, dx = if ((t / 500) % 2 == 0L) 0.02f else -0.02f) })
        assertTrue(s.started.isEmpty())
    }

    @Test fun `E2 - unapproved human food is not assumed safe`() {
        val s = Sim()
        s.run(0, 8_000, dog = { dogStill }, objects = { t -> atDog("banana", t, dx = if ((t / 500) % 2 == 0L) 0.02f else -0.02f) })
        assertTrue(s.started.isNotEmpty())
    }

    @Test fun `F - a dog approaching a restricted item alerts under a sensitive policy`() {
        val s = Sim(SafetyPolicy(sensitivity = 1f))
        s.run(0, 3_000, dog = { t -> val x = 0.0f + t / 1000f * 0.06f; BoundingBox(x, 0.30f, x + 0.30f, 0.80f) }, objects = { t -> listOf(obj("scissors", BoundingBox(0.50f, 0.55f, 0.56f, 0.61f), t)) })
        assertTrue(s.types().contains(EventType.DOG_APPROACHING_HAZARD))
    }

    @Test fun `G - sniffing rubbish is warned about`() {
        val s = Sim()
        s.run(0, 3_000, dog = { dogStill }, objects = { t -> atDog("trash", t) })
        assertTrue(s.started.isNotEmpty())
        assertEquals("RUBBISH", s.started.first().metadata["category"])
    }

    @Test fun `H - a hazard in the room with the dog far away stays quiet`() {
        val s = Sim()
        s.run(0, 60_000, stepMs = 1_000, dog = { BoundingBox(0.70f, 0.10f, 0.95f, 0.40f) }, objects = { t -> farObject("bottle", t) })
        assertTrue(s.started.isEmpty())
    }

    @Test fun `I - an object that vanishes after only a brief touch is not called ingestion`() {
        val s = Sim()
        val t1 = s.run(0, 1_000, dog = { dogStill }, objects = { t -> atDog("bottle", t) })
        s.run(t1, t1 + 8_000, dog = { dogStill }, objects = { emptyList() })
        assertFalse(s.types().contains(EventType.POSSIBLE_INGESTION))
    }

    @Test fun `I2 - an object that vanishes while the dog is out of view makes no claim`() {
        val s = Sim()
        val t1 = s.run(0, 6_000, dog = { dogStill }, objects = { t -> atDog("bottle", t, dx = if ((t / 500) % 2 == 0L) 0.02f else -0.02f) })
        s.run(t1, t1 + 8_000, dog = { null }, objects = { emptyList() })
        assertFalse(s.types().contains(EventType.POSSIBLE_INGESTION))
    }

    @Test fun `I3 - chewing then vanishing next to the dog is only POSSIBLE ingestion, worded with uncertainty, not critical`() {
        val s = Sim()
        val t1 = s.run(0, 7_000, dog = { dogStill }, objects = { t -> atDog("scissors", t, dx = if ((t / 500) % 2 == 0L) 0.02f else -0.02f) })
        s.run(t1, t1 + 5_000, dog = { dogStill }, objects = { emptyList() })
        val ing = s.started.filter { it.type == EventType.POSSIBLE_INGESTION }
        assertTrue("expected a possible-ingestion event, got ${s.started.map { it.type }}", ing.isNotEmpty())
        val e = ing.first()
        assertTrue(e.metadata.getValue("explanation").contains("may be hidden"))
        assertFalse(e.metadata.getValue("title").contains("swallowed", ignoreCase = true))
        assertNotEquals("CRITICAL", e.metadata["risk"])
    }

    @Test fun `M - one long incident is one notification, not one per frame`() {
        val s = Sim()
        s.run(0, 30_000, dog = { dogStill }, objects = { t -> atDog("bottle", t, dx = if ((t / 500) % 2 == 0L) 0.02f else -0.02f) })
        assertEquals(1, s.notifying.map { it.metadata["incidentId"] }.toSet().size)
        assertTrue("notifications: ${s.notifying.map { it.type }}", s.notifying.size <= 2)
    }

    @Test fun `escalation from sniffing to chewing creates a second, linked, notifying event`() {
        val s = Sim()
        val t1 = s.run(0, 2_500, dog = { dogStill }, objects = { t -> atDog("bottle", t) })
        s.run(t1, t1 + 8_000, dog = { dogStill }, objects = { t -> atDog("bottle", t, dx = if ((t / 500) % 2 == 0L) 0.02f else -0.02f) })
        val n = s.notifying
        assertTrue("got ${n.map { it.type to it.metadata["risk"] }}", n.size >= 2)
        assertEquals(1, n.map { it.metadata["incidentId"] }.toSet().size)
        assertEquals("true", n.last().metadata["escalation"])
    }

    @Test fun `incident closes with a duration when the interaction ends`() {
        val s = Sim()
        val t1 = s.run(0, 3_000, dog = { dogStill }, objects = { t -> atDog("bottle", t) })
        s.run(t1, t1 + 12_000, dog = { BoundingBox(0.70f, 0.05f, 0.95f, 0.30f) }, objects = { t -> farObject("bottle", t) })
        val done = s.out.filter { it.phase == EngineOutput.Phase.COMPLETED }.map { it.event }
        assertEquals(1, done.size)
        assertFalse(done.first().ongoing)
        assertTrue(done.first().durationMs > 0)
    }

    @Test fun `disabled policy produces nothing`() {
        val s = Sim(SafetyPolicy(enabled = false))
        s.run(0, 8_000, dog = { dogStill }, objects = { t -> atDog("scissors", t, dx = if ((t / 500) % 2 == 0L) 0.02f else -0.02f) })
        assertTrue(s.started.isEmpty())
    }

    @Test fun `labels the policy does not know are ignored rather than guessed`() {
        val s = Sim()
        s.run(0, 8_000, dog = { dogStill }, objects = { t -> atDog("chair", t) })
        assertTrue(s.started.isEmpty())
    }

    @Test fun `policy round-trips through JSON and survives garbage`() {
        val p = SafetyPolicy(sensitivity = 0.8f, unknownObjectAlerts = false)
        val back = SafetyPolicy.fromJson(p.toJson())
        assertEquals(0.8f, back.sensitivity, 0.001f)
        assertFalse(back.unknownObjectAlerts)
        assertEquals(p.items.size, back.items.size)
        assertTrue(SafetyPolicy.fromJson(null).items.isNotEmpty())
    }
}
