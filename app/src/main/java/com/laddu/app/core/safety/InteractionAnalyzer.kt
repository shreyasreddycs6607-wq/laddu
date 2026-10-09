package com.laddu.app.core.safety

import com.laddu.app.core.ai.BoundingBox
import com.laddu.app.core.ai.Detection
import com.laddu.app.core.ai.DogTracker
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** An object followed across frames. [seen] is false while the track is only being held through a short occlusion. */
data class TrackedObject(
    val key: String,
    val label: String,
    val box: BoundingBox,
    val confidence: Float,
    val seen: Boolean,
    val lastSeenMs: Long,
)

/**
 * Gives every non-dog detection a stable key. Reuses the dog tracker's IoU matching, one tracker per label so a
 * "bottle" can never be confused with a "scissors" that happens to sit in the same place.
 */
class ObjectTracker(private val maxMissingMs: Long = 4_000) {
    private val perLabel = HashMap<String, DogTracker>()

    fun update(detections: List<Detection>, nowMs: Long): List<TrackedObject> {
        val byLabel = detections.groupBy { it.label }
        val out = ArrayList<TrackedObject>()
        for (label in perLabel.keys + byLabel.keys) {
            val tracker = perLabel.getOrPut(label) { DogTracker(maxMissingMs = maxMissingMs) }
            tracker.update(byLabel[label].orEmpty(), nowMs) // also called with no detections so stale tracks age out
            tracker.active.forEach { t -> out += TrackedObject("$label#${t.id}", label, t.box, t.confidence, t.lastSeenMs == nowMs, t.lastSeenMs) }
        }
        perLabel.entries.removeAll { it.value.active.isEmpty() && it.key !in byLabel }
        return out
    }

    fun reset() = perLabel.clear()
}

enum class InteractionKind(val rank: Int) {
    NONE(0), NEAR(1), APPROACHING(2), SNIFFING(3), PICKUP(4), CARRYING(4), CHEWING(5), INGESTION(6),
}

/** The analyzer's view of one dog/object pair right now. [evidence] is 0..1 and is *not* a probability. */
data class Interaction(
    val dogId: Int,
    val objectKey: String,
    val label: String,
    val kind: InteractionKind,
    val evidence: Float,
    val contactMs: Long,
    val explanation: String,
    /** True when the object vanished next to the dog: we do not know where it went. */
    val objectLost: Boolean = false,
)

data class InteractionConfig(
    /** Fraction of the object's box that must lie inside the (slightly grown) dog box to count as contact. */
    val contactOverlap: Float = 0.35f,
    val nearExpand: Float = 0.35f,
    val sniffMinMs: Long = 800,
    val chewMinMs: Long = 4_000,
    val carryMinMs: Long = 1_500,
    val ingestMinContactMs: Long = 2_000,
    val lostConfirmMs: Long = 1_000,
    val forgetAfterMs: Long = 40_000,
)

/**
 * Turns "a dog box and an object box in the same place" into what is probably happening, using several frames.
 * No pose model is available, so there is no muzzle location: contact means the object lies within the dog's box,
 * which is a coarser (and lower-confidence) claim than "in its mouth". Everything it reports is an observation with
 * an evidence score; it never states that something was swallowed, only that the object vanished after contact.
 */
class InteractionAnalyzer(private val cfg: InteractionConfig = InteractionConfig()) {

    private class PairState(val startedMs: Long) {
        var lastUpdateMs = startedMs
        var lastSeenMs = startedMs
        var lastContactMs = 0L
        var contactMs = 0L
        var inContact = false
        var cycles = 0
        var stationaryMs = 0L
        var stationaryAtContact = 0L
        var carryMs = 0L
        var pickup = false
        var rel = 0f
        var relMs = 0L
        var dogMoveSum = 0f
        var samples = 0
        var confSum = 0f
        var prevObj: Pt? = null
        var prevDog: Pt? = null
        var prevDist = Float.MAX_VALUE
        var lastBox: BoundingBox? = null
        var lostSinceMs: Long? = null
        var ingestFlagged = false
        var everChewing = false
        var everCarrying = false
    }

    private data class Pt(val x: Float, val y: Float)

    private val pairs = HashMap<String, PairState>()

    fun update(dogs: List<DogTracker.Track>, objects: List<TrackedObject>, nowMs: Long): List<Interaction> {
        val liveDogs = dogs.associateBy { it.id }
        val liveObjects = objects.associateBy { it.key }

        // create state for new near/contact pairs
        for (d in dogs) for (o in objects) {
            if (!o.seen) continue
            val key = "${d.id}|${o.key}"
            if (key !in pairs && d.box.expanded(cfg.nearExpand).intersects(o.box)) pairs[key] = PairState(nowMs)
        }

        val out = ArrayList<Interaction>()
        val dead = ArrayList<String>()
        for ((key, st) in pairs) {
            val dogId = key.substringBefore('|').toInt()
            val objKey = key.substringAfter('|')
            val d = liveDogs[dogId]
            val o = liveObjects[objKey]
            val dt = (nowMs - st.lastUpdateMs).coerceIn(0, 2_500)
            st.lastUpdateMs = nowMs

            if (o != null && o.seen && d != null) seen(st, d, o, dt, nowMs)
            else missing(st, d, o, nowMs)

            val label = objKey.substringBefore('#')
            val kind = classify(st, d != null, nowMs)
            if (kind != InteractionKind.NONE) out += describe(dogId, objKey, label, kind, st, nowMs)

            if (nowMs - st.lastSeenMs > cfg.forgetAfterMs || (nowMs - st.lastUpdateMs > cfg.forgetAfterMs)) dead += key
            else if (kind == InteractionKind.NONE && !st.inContact && nowMs - st.lastContactMs > 15_000 && nowMs - st.startedMs > 15_000) dead += key
        }
        dead.forEach { pairs.remove(it) }
        return out
    }

    private fun seen(st: PairState, d: DogTracker.Track, o: TrackedObject, dt: Long, now: Long) {
        val oc = Pt(o.box.centerX, o.box.centerY)
        val dc = Pt(d.box.centerX, d.box.centerY)
        val po = st.prevObj; val pd = st.prevDog
        val oMove = if (po != null) dist(oc, po) else 0f
        val dMove = if (pd != null) dist(dc, pd) else 0f
        val rel = if (po != null && pd != null) hypot((oc.x - po.x) - (dc.x - pd.x), (oc.y - po.y) - (dc.y - pd.y)) else 0f

        if (oMove < STILL) st.stationaryMs += dt else st.stationaryMs = 0

        val contact = overlapFraction(o.box, d.box.expanded(0.06f)) >= cfg.contactOverlap
        if (contact) {
            if (!st.inContact) { st.cycles++; st.stationaryAtContact = st.stationaryMs }
            st.inContact = true
            st.contactMs += dt
            st.lastContactMs = now
            st.lastBox = o.box
            st.dogMoveSum += dMove
            // Only frames where the OBJECT itself moved count as "shifting against the dog". A dog walking over a
            // still object moves relative to it too, but that is walking, not chewing.
            if (oMove > STILL) { st.rel += rel; st.relMs += dt }
            val together = oMove > STILL && dMove > STILL && rel < 0.6f * max(oMove, dMove)
            if (together) st.carryMs += dt
            if (st.carryMs > 0 && st.stationaryAtContact >= 1_500) st.pickup = true
        } else {
            st.inContact = false
        }
        st.samples++
        st.confSum += o.confidence
        st.prevObj = oc; st.prevDog = dc
        st.prevDist = dist(oc, dc)
        st.lastSeenMs = now
        st.lostSinceMs = null
    }

    private fun missing(st: PairState, d: DogTracker.Track?, o: TrackedObject?, now: Long) {
        if (st.lostSinceMs == null) st.lostSinceMs = now
        st.inContact = false
        val lost = st.lostSinceMs ?: now
        val lastBox = st.lastBox
        // Only a dog that is still in view, right where the object last was, after real contact, can be "object vanished
        // next to the dog". A dog that left the frame, or an object that never touched the dog, makes no claim.
        if (!st.ingestFlagged && d != null && lastBox != null &&
            st.contactMs >= cfg.ingestMinContactMs && now - st.lastContactMs <= 2_500 && now - lost >= cfg.lostConfirmMs &&
            contains(d.box.expanded(0.06f), lastBox.centerX, lastBox.centerY)
        ) st.ingestFlagged = true
    }

    private fun classify(st: PairState, dogVisible: Boolean, now: Long): InteractionKind {
        if (st.ingestFlagged && now - (st.lostSinceMs ?: now) < cfg.forgetAfterMs) return InteractionKind.INGESTION
        if (!dogVisible || st.lostSinceMs != null) return InteractionKind.NONE
        val contactSec = (st.contactMs / 1000f).coerceAtLeast(0.001f)
        val jitterPerSec = st.rel / contactSec
        // Sniffing and chewing need a dog that is not travelling: a walking dog is just passing the object.
        val slowDog = st.dogMoveSum / contactSec < SLOW_DOG
        val chewing = st.inContact && slowDog && st.contactMs >= cfg.chewMinMs && st.carryMs < st.contactMs / 2 &&
            (jitterPerSec >= CHEW_JITTER || (st.cycles >= 3 && st.contactMs >= 3_000))
        if (chewing) { st.everChewing = true; return InteractionKind.CHEWING }
        if (st.inContact && st.carryMs >= cfg.carryMinMs) {
            st.everCarrying = true
            return if (st.pickup && st.carryMs < 3_000) InteractionKind.PICKUP else InteractionKind.CARRYING
        }
        if (st.inContact && slowDog && st.contactMs >= cfg.sniffMinMs) return InteractionKind.SNIFFING
        if (st.inContact) return InteractionKind.NEAR
        val nearNow = st.prevDist < 0.45f
        if (nearNow && now - st.lastSeenMs <= 1_500) return if (approaching(st)) InteractionKind.APPROACHING else InteractionKind.NEAR
        return InteractionKind.NONE
    }

    private fun approaching(st: PairState) = st.prevDist < 0.30f && st.contactMs == 0L

    private fun describe(dogId: Int, key: String, label: String, kind: InteractionKind, st: PairState, now: Long): Interaction {
        val conf = if (st.samples > 0) st.confSum / st.samples else 0f
        var e = when (kind) {
            InteractionKind.NEAR -> 0.20f
            InteractionKind.APPROACHING -> 0.30f
            InteractionKind.SNIFFING -> 0.40f
            InteractionKind.PICKUP -> 0.65f
            InteractionKind.CARRYING -> 0.60f
            InteractionKind.CHEWING -> 0.70f
            InteractionKind.INGESTION -> 0.55f + (if (st.everChewing) 0.20f else 0f) + (if (st.everCarrying || st.pickup) 0.10f else 0f)
            InteractionKind.NONE -> 0f
        }
        e += 0.15f * min(1f, st.contactMs / 10_000f)
        e *= 0.6f + 0.4f * conf // a shaky detector means shaky evidence
        if (st.samples < 3) e *= 0.7f
        val why = when (kind) {
            InteractionKind.NEAR -> "The $label is close to the dog."
            InteractionKind.APPROACHING -> "The dog is moving toward the $label."
            InteractionKind.SNIFFING -> "The dog has been at the $label for ${st.contactMs / 1000}s without moving it (sniffing-like)."
            InteractionKind.PICKUP -> "The $label was still, then moved together with the dog (picked up)."
            InteractionKind.CARRYING -> "The $label is moving with the dog (carried)."
            InteractionKind.CHEWING -> "The $label has stayed at the dog for ${st.contactMs / 1000}s and keeps shifting against it (chewing-like)."
            InteractionKind.INGESTION -> "The $label vanished right where the dog is after ${st.contactMs / 1000}s of contact. It may be hidden by the dog's body or swallowed; the camera cannot tell which."
            InteractionKind.NONE -> ""
        }
        return Interaction(dogId, key, label, kind, e.coerceIn(0f, 0.95f), st.contactMs, why, objectLost = kind == InteractionKind.INGESTION)
    }

    fun reset() = pairs.clear()

    private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)
    private fun contains(b: BoundingBox, x: Float, y: Float) = x in b.left..b.right && y in b.top..b.bottom

    private fun overlapFraction(obj: BoundingBox, region: BoundingBox): Float {
        val l = max(obj.left, region.left); val t = max(obj.top, region.top)
        val r = min(obj.right, region.right); val b = min(obj.bottom, region.bottom)
        val inter = (r - l).coerceAtLeast(0f) * (b - t).coerceAtLeast(0f)
        return if (obj.area <= 0f) 0f else inter / obj.area
    }

    private companion object {
        const val STILL = 0.012f
        const val CHEW_JITTER = 0.02f
        /** Average dog travel (fraction of frame per second) above which it counts as walking, not sniffing. */
        const val SLOW_DOG = 0.05f
    }
}
