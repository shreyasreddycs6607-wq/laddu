package com.laddu.app.core.safety

import com.laddu.app.core.ai.Detection
import com.laddu.app.core.ai.DogTracker
import com.laddu.app.core.events.EngineOutput
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import java.util.Locale
import java.util.UUID

/** Result of judging one interaction against the owner's policy. */
data class Assessment(val risk: RiskLevel, val type: EventType)

/**
 * Local, offline hazard logic. Feed it one frame of dog tracks and object detections per inference; it returns
 * events in the same [EngineOutput] shape the rest of the app already stores, syncs and pushes.
 *
 * Nothing here waits for the network or for cloud AI: a qualifying interaction becomes a STARTED event at once.
 * One continuing situation is ONE incident: it is updated in place, and only a genuine rise in risk produces a new
 * (notifying) event, linked by `incidentId`.
 */
class HazardEngine(
    private val cameraId: String,
    private val ownerId: String,
    var policy: SafetyPolicy = SafetyPolicy(),
    private val analyzer: InteractionAnalyzer = InteractionAnalyzer(),
    private val objects: ObjectTracker = ObjectTracker(),
    private val endGapMs: Long = 6_000,
    private val ingestionEndGapMs: Long = 20_000,
    idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val ids = idGenerator

    private class Incident(val id: String, val label: String, val objectKey: String, var current: LadduEvent, var risk: RiskLevel) {
        var kind: InteractionKind = InteractionKind.NONE
        var startedMs: Long = current.timestamp
        var lastActiveMs: Long = current.timestamp
    }

    private val incidents = HashMap<String, Incident>()

    /** Objects the camera currently follows that are part of the policy (for diagnostics and the debug overlay). */
    var trackedObjects: List<TrackedObject> = emptyList()
        private set

    fun onFrame(dogTracks: List<DogTracker.Track>, objectDetections: List<Detection>, nowMs: Long): List<EngineOutput> {
        if (!policy.enabled) return flush(nowMs)
        val tracked = objects.update(objectDetections.filter { policy.match(it.label) != null }, nowMs)
        trackedObjects = tracked
        val out = ArrayList<EngineOutput>()
        val active = HashSet<String>()

        for (i in analyzer.update(dogTracks, tracked, nowMs)) {
            val item = policy.match(i.label) ?: continue
            val a = assess(item, i) ?: continue
            val key = "${i.dogId}|${i.objectKey}"
            active += key
            val inc = incidents[key]
            if (inc == null) {
                if (a.risk >= RiskLevel.CAUTION && item.notify) out += open(key, item, i, a, nowMs)
                continue
            }
            inc.lastActiveMs = nowMs
            inc.kind = i.kind
            when {
                a.risk > inc.risk -> { // genuinely more serious: close the old event, start a notifying one
                    out += EngineOutput(EngineOutput.Phase.COMPLETED, inc.current.copy(ongoing = false, durationMs = nowMs - inc.startedMs))
                    val next = event(a.type, nowMs, i.evidence, meta(item, i, a, inc.id, escalation = true), notify = true)
                    inc.current = next; inc.risk = a.risk; inc.startedMs = nowMs
                    out += EngineOutput(EngineOutput.Phase.STARTED, next)
                }
                a.type != inc.current.type || i.evidence > inc.current.confidence + 0.1f -> { // same risk: update in place, no new push
                    inc.current = inc.current.copy(type = a.type, confidence = i.evidence, metadata = meta(item, i, a, inc.id, escalation = false))
                    out += EngineOutput(EngineOutput.Phase.STARTED, inc.current)
                }
            }
        }

        // An ingestion-style incident whose object turns up again was probably just hidden behind the dog.
        for ((key, inc) in incidents.entries.toList()) {
            if (inc.kind == InteractionKind.INGESTION && tracked.any { it.seen && it.label == inc.label && it.key != inc.objectKey }) {
                out += close(inc, nowMs, "object_reappeared"); incidents.remove(key); continue
            }
            val gap = if (inc.kind == InteractionKind.INGESTION) ingestionEndGapMs else endGapMs
            if (key !in active && nowMs - inc.lastActiveMs >= gap) { out += close(inc, nowMs, "ended"); incidents.remove(key) }
        }
        return out
    }

    /** Close everything still open (camera stopping, feature switched off). */
    fun flush(nowMs: Long): List<EngineOutput> {
        val out = incidents.values.map { close(it, nowMs, "monitoring_stopped") }
        incidents.clear()
        return out
    }

    fun reset() { incidents.clear(); analyzer.reset(); objects.reset(); trackedObjects = emptyList() }

    // ------------------------------------------------------------------ policy

    /** Null when nothing worth recording happened. Approved items never raise a hazard. */
    internal fun assess(item: SafetyItem, i: Interaction): Assessment? {
        if (item.approval == Approval.APPROVED || item.risk == RiskLevel.INFO) return null
        val e = i.evidence
        val p = policy
        val risk = when (i.kind) {
            InteractionKind.NONE, InteractionKind.NEAR -> RiskLevel.INFO
            InteractionKind.APPROACHING, InteractionKind.SNIFFING -> if (e >= p.cautionMin) RiskLevel.CAUTION else RiskLevel.INFO
            InteractionKind.PICKUP, InteractionKind.CARRYING, InteractionKind.CHEWING -> when {
                e >= p.highMin -> RiskLevel.HIGH
                e >= p.cautionMin -> RiskLevel.CAUTION
                else -> RiskLevel.INFO
            }
            InteractionKind.INGESTION -> when {
                // reserved for hazardous items with strong, repeated evidence before the object vanished
                item.risk == RiskLevel.HIGH && e >= p.criticalMin -> RiskLevel.CRITICAL
                e >= p.highMin -> RiskLevel.HIGH
                e >= p.cautionMin -> RiskLevel.CAUTION
                else -> RiskLevel.INFO
            }
        }
        if (risk == RiskLevel.INFO) return null
        val type = when (i.kind) {
            InteractionKind.APPROACHING -> EventType.DOG_APPROACHING_HAZARD
            InteractionKind.NEAR, InteractionKind.SNIFFING ->
                if (item.category == HazardCategory.UNKNOWN) EventType.UNKNOWN_OBJECT_NEAR_MOUTH else EventType.POSSIBLE_HAZARD_INTERACTION
            InteractionKind.PICKUP, InteractionKind.CARRYING ->
                if (risk >= RiskLevel.HIGH) EventType.HIGH_RISK_OBJECT_INTERACTION else EventType.POSSIBLE_HAZARD_INTERACTION
            InteractionKind.CHEWING -> if (item.category == HazardCategory.UNKNOWN) EventType.UNKNOWN_OBJECT_NEAR_MOUTH else EventType.POSSIBLE_CHEWING
            InteractionKind.INGESTION -> EventType.POSSIBLE_INGESTION
            InteractionKind.NONE -> return null
        }
        return Assessment(risk, type)
    }

    // ------------------------------------------------------------------ events

    private fun open(key: String, item: SafetyItem, i: Interaction, a: Assessment, now: Long): List<EngineOutput> {
        val incidentId = ids()
        val e = event(a.type, now, i.evidence, meta(item, i, a, incidentId, escalation = false), notify = true)
        incidents[key] = Incident(incidentId, i.label, i.objectKey, e, a.risk).also { it.kind = i.kind }
        return listOf(EngineOutput(EngineOutput.Phase.STARTED, e))
    }

    private fun close(inc: Incident, now: Long, outcome: String): EngineOutput {
        val end = if (outcome == "ended") inc.lastActiveMs else now
        return EngineOutput(
            EngineOutput.Phase.COMPLETED,
            inc.current.copy(ongoing = false, durationMs = (end - inc.startedMs).coerceAtLeast(0), metadata = inc.current.metadata + ("outcome" to outcome)),
        )
    }

    private fun event(type: EventType, ts: Long, evidence: Float, meta: Map<String, String>, notify: Boolean) =
        LadduEvent(ids(), cameraId, ownerId, type, ts, 0, evidence, true, meta, notify = notify)

    private fun meta(item: SafetyItem, i: Interaction, a: Assessment, incidentId: String, escalation: Boolean): Map<String, String> {
        val evidence = String.format(Locale.US, "%.2f", i.evidence)
        return mapOf(
            "incidentId" to incidentId,
            "risk" to a.risk.name,
            "object" to i.label,
            "objectName" to item.name,
            "category" to item.category.name,
            "approval" to item.approval.name,
            "interaction" to i.kind.name,
            "evidence" to evidence,
            "explanation" to i.explanation,
            "action" to HazardText.action(a.risk),
            "title" to HazardText.title(a.type, a.risk),
            "body" to HazardText.body(a.type, a.risk, item.name, i.explanation),
            "escalation" to escalation.toString(),
            "source" to "local",
        )
    }
}

/** Wording is deliberately cautious: these are camera observations, not diagnoses. */
object HazardText {
    fun action(r: RiskLevel) = when (r) {
        RiskLevel.INFO -> "No action needed."
        RiskLevel.CAUTION -> "Keep an eye on the camera."
        RiskLevel.HIGH -> "Check the live camera now."
        RiskLevel.CRITICAL -> "Check on your dog immediately. If you think something was swallowed, contact your vet."
    }

    fun title(t: EventType, r: RiskLevel): String = when (t) {
        EventType.POSSIBLE_INGESTION -> if (r >= RiskLevel.CRITICAL) "🚨 Possible ingestion" else "⚠️ Possible ingestion"
        EventType.POSSIBLE_CHEWING -> "⚠️ Possible chewing of a hazard"
        EventType.HIGH_RISK_OBJECT_INTERACTION -> "⚠️ High-risk object interaction"
        EventType.UNKNOWN_OBJECT_NEAR_MOUTH -> "⚠️ Unidentified object near your dog"
        EventType.DOG_APPROACHING_HAZARD -> "👀 Dog approaching a hazard"
        else -> "⚠️ Possible hazard interaction"
    }

    fun body(t: EventType, r: RiskLevel, item: String, explanation: String): String =
        "$item: $explanation ${action(r)}".trim()
}
