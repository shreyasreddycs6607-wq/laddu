package com.laddu.app.core.events

import com.laddu.app.core.model.CameraSettings
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import java.util.UUID

/** Tunables for turning raw detections into a few meaningful events. */
data class DetectionConfig(
    // --- dog presence
    val presenceAbsentMs: Long = 45_000,
    /** A dog seen again after being gone this long counts as "returned" (notifies). */
    val returnMinAbsentMs: Long = 2 * 60_000,
    /** "Camera back online" only notifies after an outage at least this long (matches the server's offline cutoff). */
    val onlineNotifyMinOfflineMs: Long = 2 * 60_000,
    // --- movement
    /** Confirmed movement samples needed within [movementStartWindowMs] to start an event. */
    val movementStartSamples: Int = 3,
    val movementStartWindowMs: Long = 5_000,
    /** No confirmed movement for this long ends the event; shorter pauses stay in the same event. */
    val movementEndGapMs: Long = 10_000,
    val movementNotifyCooldownMs: Long = 5 * 60_000,
    // --- barking
    val barkCount: Int = 3,
    val barkWindowMs: Long = 30_000,
    val barkEndGapMs: Long = 8_000,
    /** Approximate audible length of one detected bark window. */
    val barkSampleMs: Long = 1_000,
    val repeatedBarkCount: Int = 5,
    val repeatedBarkWindowMs: Long = 5 * 60_000,
    val repeatedBarkCooldownMs: Long = 10 * 60_000,
    // --- howling
    val howlEndGapMs: Long = 10_000,
    val howlNotifyCooldownMs: Long = 5 * 60_000,
    // --- system
    val lowBatteryPct: Int = 15,
    val lowBatteryRearmPct: Int = 25,
) {
    companion object {
        fun from(s: CameraSettings) = DetectionConfig(
            barkCount = s.barkCount,
            barkWindowMs = s.barkWindowSec * 1000L,
        )
    }
}

sealed interface EngineInput {
    val ts: Long

    data class DogSeen(override val ts: Long, val confidence: Float) : EngineInput
    data class DogAbsent(override val ts: Long) : EngineInput
    /** [confirmed] = motion was attributed to the dog (not a light flicker or a person). */
    data class MovementSample(override val ts: Long, val confirmed: Boolean, val amount: Float = 0f) : EngineInput
    data class BarkSample(override val ts: Long, val confidence: Float) : EngineInput
    data class HowlSample(override val ts: Long, val confidence: Float) : EngineInput
    data class NetworkChanged(override val ts: Long, val online: Boolean) : EngineInput
    data class BatteryChanged(override val ts: Long, val pct: Int, val charging: Boolean) : EngineInput
    /** Heartbeat so timers can expire when no samples arrive. Call about once a second. */
    data class Tick(override val ts: Long) : EngineInput
}

/** What happened to a logical event. The same eventId appears in STARTED then COMPLETED. */
data class EngineOutput(val phase: Phase, val event: LadduEvent) {
    enum class Phase { STARTED, COMPLETED, INSTANT }
}

data class LiveDetectionStatus(val dogPresent: Boolean, val moving: Boolean, val barking: Boolean)

internal class IdSource(private val gen: () -> String) { fun next() = gen() }

class EventEngine(
    private val cameraId: String,
    private val ownerId: String,
    var config: DetectionConfig = DetectionConfig(),
    idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val ids = IdSource(idGenerator)

    private fun event(type: EventType, ts: Long, confidence: Float, meta: Map<String, String> = emptyMap(), notify: Boolean = true) =
        LadduEvent(ids.next(), cameraId, ownerId, type, ts, 0, confidence, false, meta, notify = notify)

    // ---------------------------------------------------------------- presence
    private var presentSince: Long? = null
    private var presenceEvent: LadduEvent? = null
    private var lastSeenTs: Long = 0
    private var everSeen = false
    private var bestConfidence = 0f

    private fun onDogSeen(i: EngineInput.DogSeen, out: MutableList<EngineOutput>) {
        val gap = if (everSeen) i.ts - lastSeenTs else Long.MAX_VALUE
        lastSeenTs = i.ts
        bestConfidence = if (presenceEvent == null) i.confidence else maxOf(bestConfidence, i.confidence)
        if (presenceEvent == null) {
            val returned = everSeen && gap >= config.returnMinAbsentMs
            val e = event(
                EventType.DOG_PRESENCE, i.ts, i.confidence,
                mapOf("returned" to returned.toString()),
                notify = returned, // the very first sighting just updates the dashboard
            ).copy(ongoing = true)
            presenceEvent = e; presentSince = i.ts
            out += EngineOutput(EngineOutput.Phase.STARTED, e)
        }
        everSeen = true
    }

    private fun expirePresence(now: Long, out: MutableList<EngineOutput>) {
        val e = presenceEvent ?: return
        if (now - lastSeenTs >= config.presenceAbsentMs) {
            out += EngineOutput(
                EngineOutput.Phase.COMPLETED,
                e.copy(ongoing = false, durationMs = (lastSeenTs - e.timestamp).coerceAtLeast(0), confidence = bestConfidence),
            )
            presenceEvent = null; presentSince = null
        }
    }

    // ---------------------------------------------------------------- movement
    private val confirmedSamples = ArrayDeque<Long>()
    private var movementEvent: LadduEvent? = null
    private var lastMovementTs = 0L
    private var lastMovementNotifyTs = Long.MIN_VALUE / 2
    private var movementPeak = 0f

    private fun onMovement(i: EngineInput.MovementSample, out: MutableList<EngineOutput>) {
        if (!i.confirmed) return
        lastMovementTs = i.ts
        movementPeak = maxOf(movementPeak, i.amount)
        if (movementEvent != null) return
        confirmedSamples.addLast(i.ts)
        while (confirmedSamples.isNotEmpty() && i.ts - confirmedSamples.first() > config.movementStartWindowMs) confirmedSamples.removeFirst()
        if (confirmedSamples.size >= config.movementStartSamples) {
            val start = confirmedSamples.first()
            val notify = start - lastMovementNotifyTs >= config.movementNotifyCooldownMs
            if (notify) lastMovementNotifyTs = start
            val e = event(EventType.MOVEMENT, start, 0.9f, notify = notify).copy(ongoing = true)
            movementEvent = e
            movementPeak = i.amount
            confirmedSamples.clear()
            out += EngineOutput(EngineOutput.Phase.STARTED, e)
        }
    }

    private fun expireMovement(now: Long, out: MutableList<EngineOutput>) {
        // stale start candidates must not combine with samples from long ago
        while (confirmedSamples.isNotEmpty() && now - confirmedSamples.first() > config.movementStartWindowMs) confirmedSamples.removeFirst()
        val e = movementEvent ?: return
        if (now - lastMovementTs >= config.movementEndGapMs) {
            out += EngineOutput(
                EngineOutput.Phase.COMPLETED,
                e.copy(
                    ongoing = false,
                    durationMs = (lastMovementTs - e.timestamp).coerceAtLeast(0),
                    metadata = e.metadata + ("peak" to "%.2f".format(movementPeak)),
                ),
            )
            movementEvent = null
        }
    }

    // ---------------------------------------------------------------- barking
    private val barkTimes = ArrayDeque<Long>()
    private val barkConfs = ArrayDeque<Float>()
    private var barkEvent: LadduEvent? = null
    private var lastBarkTs = 0L
    private var barkCountInEvent = 0
    private var barkConfSum = 0f
    private val barkEventStarts = ArrayDeque<Long>()
    private var lastRepeatedTs = Long.MIN_VALUE / 2

    private fun onBark(i: EngineInput.BarkSample, out: MutableList<EngineOutput>) {
        lastBarkTs = i.ts
        val running = barkEvent
        if (running != null) {
            barkCountInEvent++; barkConfSum += i.confidence
            return
        }
        barkTimes.addLast(i.ts); barkConfs.addLast(i.confidence)
        while (barkTimes.isNotEmpty() && i.ts - barkTimes.first() > config.barkWindowMs) { barkTimes.removeFirst(); barkConfs.removeFirst() }
        if (barkTimes.size >= config.barkCount) {
            val start = barkTimes.first()
            val avg = barkConfs.average().toFloat()
            val e = event(
                EventType.BARK, start, avg,
                mapOf("count" to barkTimes.size.toString()),
            ).copy(ongoing = true, durationMs = i.ts - start + config.barkSampleMs)
            barkEvent = e
            barkCountInEvent = barkTimes.size
            barkConfSum = barkConfs.sum()
            barkTimes.clear(); barkConfs.clear()
            out += EngineOutput(EngineOutput.Phase.STARTED, e)
            registerBarkEventStart(start, out)
        }
    }

    private fun registerBarkEventStart(ts: Long, out: MutableList<EngineOutput>) {
        barkEventStarts.addLast(ts)
        while (barkEventStarts.isNotEmpty() && ts - barkEventStarts.first() > config.repeatedBarkWindowMs) barkEventStarts.removeFirst()
        if (barkEventStarts.size >= config.repeatedBarkCount && ts - lastRepeatedTs >= config.repeatedBarkCooldownMs) {
            lastRepeatedTs = ts
            out += EngineOutput(
                EngineOutput.Phase.INSTANT,
                event(EventType.REPEATED_BARK, ts, 0.9f, mapOf("count" to barkEventStarts.size.toString())),
            )
        }
    }

    private fun expireBark(now: Long, out: MutableList<EngineOutput>) {
        while (barkTimes.isNotEmpty() && now - barkTimes.first() > config.barkWindowMs) { barkTimes.removeFirst(); barkConfs.removeFirst() }
        val e = barkEvent ?: return
        if (now - lastBarkTs >= config.barkEndGapMs) {
            out += EngineOutput(
                EngineOutput.Phase.COMPLETED,
                e.copy(
                    ongoing = false,
                    durationMs = lastBarkTs - e.timestamp + config.barkSampleMs,
                    confidence = barkConfSum / barkCountInEvent.coerceAtLeast(1),
                    metadata = mapOf("count" to barkCountInEvent.toString()),
                ),
            )
            barkEvent = null
        }
    }

    // ---------------------------------------------------------------- howling
    private var howlEvent: LadduEvent? = null
    private var lastHowlTs = 0L
    private var lastHowlNotifyTs = Long.MIN_VALUE / 2

    private fun onHowl(i: EngineInput.HowlSample, out: MutableList<EngineOutput>) {
        lastHowlTs = i.ts
        if (howlEvent != null) return
        val notify = i.ts - lastHowlNotifyTs >= config.howlNotifyCooldownMs
        if (notify) lastHowlNotifyTs = i.ts
        val e = event(EventType.HOWL, i.ts, i.confidence, notify = notify).copy(ongoing = true)
        howlEvent = e
        out += EngineOutput(EngineOutput.Phase.STARTED, e)
    }

    private fun expireHowl(now: Long, out: MutableList<EngineOutput>) {
        val e = howlEvent ?: return
        if (now - lastHowlTs >= config.howlEndGapMs) {
            out += EngineOutput(EngineOutput.Phase.COMPLETED, e.copy(ongoing = false, durationMs = lastHowlTs - e.timestamp + config.barkSampleMs))
            howlEvent = null
        }
    }

    // ---------------------------------------------------------------- system
    private var online = true
    private var offlineSince: Long? = null
    private var lowBatteryArmed = true

    private fun onNetwork(i: EngineInput.NetworkChanged, out: MutableList<EngineOutput>) {
        if (i.online == online) return
        online = i.online
        if (!i.online) {
            offlineSince = i.ts
            // The cloud cannot hear us while offline; the server raises the push (see functions/index.js).
            // This copy is kept locally so the camera's own history is complete.
            out += EngineOutput(
                EngineOutput.Phase.INSTANT,
                event(EventType.CAMERA_OFFLINE, i.ts, 1f, mapOf(SOURCE_LOCAL to "true"), notify = false),
            )
        } else {
            val since = offlineSince
            offlineSince = null
            out += EngineOutput(
                EngineOutput.Phase.INSTANT,
                // the server only announces "offline" after a while: a short blip must not produce a lone "back online" push
                event(EventType.CAMERA_ONLINE, i.ts, 1f, notify = (if (since != null) i.ts - since else 0) >= config.onlineNotifyMinOfflineMs)
                    .copy(durationMs = if (since != null) i.ts - since else 0),
            )
        }
    }

    private fun onBattery(i: EngineInput.BatteryChanged, out: MutableList<EngineOutput>) {
        if (i.pct < 0) return
        if (i.charging || i.pct >= config.lowBatteryRearmPct) { lowBatteryArmed = true; return }
        if (lowBatteryArmed && i.pct <= config.lowBatteryPct) {
            lowBatteryArmed = false
            out += EngineOutput(EngineOutput.Phase.INSTANT, event(EventType.LOW_BATTERY, i.ts, 1f, mapOf("battery" to i.pct.toString())))
        }
    }

    // ---------------------------------------------------------------- public API
    fun onInput(input: EngineInput): List<EngineOutput> {
        val out = ArrayList<EngineOutput>(2)
        // Advance the clock first: an event that already timed out must close BEFORE this input
        // is applied, otherwise a new sample would silently extend a stale event.
        expirePresence(input.ts, out)
        expireMovement(input.ts, out)
        expireBark(input.ts, out)
        expireHowl(input.ts, out)
        when (input) {
            is EngineInput.DogSeen -> onDogSeen(input, out)
            is EngineInput.DogAbsent -> Unit
            is EngineInput.MovementSample -> onMovement(input, out)
            is EngineInput.BarkSample -> onBark(input, out)
            is EngineInput.HowlSample -> onHowl(input, out)
            is EngineInput.NetworkChanged -> onNetwork(input, out)
            is EngineInput.BatteryChanged -> onBattery(input, out)
            is EngineInput.Tick -> Unit
        }
        return out
    }

    fun status(now: Long) = LiveDetectionStatus(
        dogPresent = presenceEvent != null && now - lastSeenTs < config.presenceAbsentMs,
        moving = movementEvent != null && now - lastMovementTs < config.movementEndGapMs,
        barking = barkEvent != null && now - lastBarkTs < config.barkEndGapMs,
    )

    /** Close every running event (monitoring stopped). */
    fun flush(now: Long): List<EngineOutput> {
        val out = ArrayList<EngineOutput>()
        presenceEvent?.let { out += EngineOutput(EngineOutput.Phase.COMPLETED, it.copy(ongoing = false, durationMs = (lastSeenTs - it.timestamp).coerceAtLeast(0))) }
        movementEvent?.let { out += EngineOutput(EngineOutput.Phase.COMPLETED, it.copy(ongoing = false, durationMs = (lastMovementTs - it.timestamp).coerceAtLeast(0))) }
        barkEvent?.let { out += EngineOutput(EngineOutput.Phase.COMPLETED, it.copy(ongoing = false, durationMs = (lastBarkTs - it.timestamp + config.barkSampleMs).coerceAtLeast(0))) }
        howlEvent?.let { out += EngineOutput(EngineOutput.Phase.COMPLETED, it.copy(ongoing = false, durationMs = (lastHowlTs - it.timestamp).coerceAtLeast(0))) }
        presenceEvent = null; movementEvent = null; barkEvent = null; howlEvent = null
        return out
    }

    companion object {
        /** Metadata marker: keep this event on the device, never upload it. */
        const val SOURCE_LOCAL = "localOnly"
    }
}
