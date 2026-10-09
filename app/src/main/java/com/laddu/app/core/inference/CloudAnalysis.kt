package com.laddu.app.core.inference

import org.json.JSONObject

/** One optional second opinion on a hazard event. An *interpretation* of the evidence sent, never a verified fact. */
data class CloudAnalysis(
    val eventId: String,
    val objects: List<String>,
    val dogActivity: String,
    val suspectedInteraction: String,
    val evidence: String,
    val hazardCategory: String,
    /** 0..1 as reported by the model; treat as a rough guide only. */
    val confidence: Float,
    val recommendedAction: String,
    val modelId: String,
    val analyzedAtMs: Long,
) {
    /** Keys stored on the event (prefixed so they can never overwrite what the local detector recorded). */
    fun toMetadata(): Map<String, String> = mapOf(
        "cloud_summary" to evidence,
        "cloud_objects" to objects.joinToString(", "),
        "cloud_activity" to dogActivity,
        "cloud_interaction" to suspectedInteraction,
        "cloud_category" to hazardCategory,
        "cloud_confidence" to "%.2f".format(java.util.Locale.US, confidence),
        "cloud_action" to recommendedAction,
        "cloud_model" to modelId,
        "cloud_at" to analyzedAtMs.toString(),
    )
}

/** What is sent for analysis: one small JPEG plus the local detector's own description. Nothing else leaves the phone. */
class AnalysisRequest(
    val eventId: String,
    val cameraId: String,
    val jpeg: ByteArray,
    val localSummary: String,
)

object CloudAnalysisParser {
    const val MAX_TEXT = 600
    private val CATEGORIES = setOf("PLASTIC", "PACKAGING", "RUBBISH", "TEXTILE", "SMALL_OBJECT", "SHARP", "BATTERY", "TOXIC_FOOD", "SPOILED_FOOD", "FAECES", "FOOD", "TOY", "CONTAINER", "UNKNOWN", "NONE")

    /**
     * Strictly validates a provider response. Anything missing, oversized, of the wrong type or for a different
     * event is rejected: a malformed or hostile response must never reach storage or the UI.
     */
    fun parse(raw: Map<*, *>?, expectedEventId: String, nowMs: Long = System.currentTimeMillis()): Result<CloudAnalysis> = runCatching {
        requireNotNull(raw) { "empty response" }
        val j = JSONObject(raw.entries.associate { it.key.toString() to it.value })
        require(j.optString("eventId") == expectedEventId) { "response is for a different event" }
        fun text(key: String, required: Boolean = true): String {
            val v = j.optString(key, "").trim()
            require(!required || v.isNotEmpty()) { "missing $key" }
            require(v.length <= MAX_TEXT) { "$key too long" }
            return v
        }
        val objects = j.optJSONArray("objects")?.let { a -> (0 until a.length()).map { a.optString(it).trim().take(60) }.filter { it.isNotEmpty() }.take(12) }.orEmpty()
        val conf = j.optDouble("confidence", Double.NaN)
        require(!conf.isNaN() && conf in 0.0..1.0) { "confidence out of range" }
        val category = text("hazardCategory").uppercase()
        require(category in CATEGORIES) { "unknown hazard category" }
        CloudAnalysis(
            eventId = expectedEventId,
            objects = objects,
            dogActivity = text("dogActivity"),
            suspectedInteraction = text("suspectedInteraction"),
            evidence = text("evidence"),
            hazardCategory = category,
            confidence = conf.toFloat(),
            recommendedAction = text("recommendedAction", required = false),
            modelId = text("modelId").take(80),
            analyzedAtMs = nowMs,
        )
    }
}

/** Anything that can analyse hazard evidence. The app never depends on a particular vendor. */
interface DogSafetyInferenceProvider {
    val id: String
    suspend fun analyze(request: AnalysisRequest): Result<CloudAnalysis>
}
