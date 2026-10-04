package com.laddu.app.core.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.laddu.app.core.model.AiPerformanceMode
import com.laddu.app.core.model.CameraInfo
import com.laddu.app.core.model.CameraStatus
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import com.laddu.app.core.model.ThermalLevel
import com.laddu.app.core.model.ViewerAccess
import com.laddu.app.core.model.enumOr

/** Explicit maps (not reflection) so ProGuard/R8 can never break serialisation. */
fun LadduEvent.toMap(): Map<String, Any?> = mapOf(
    "eventId" to eventId,
    "cameraId" to cameraId,
    "ownerId" to ownerId,
    "type" to type.name,
    "timestamp" to timestamp,
    "durationMs" to durationMs,
    "confidence" to confidence.toDouble(),
    "ongoing" to ongoing,
    "metadata" to metadata,
    "snapshotRef" to snapshotRef,
    "clipRef" to clipRef,
    "notify" to notify,
)

fun DocumentSnapshot.toEvent(): LadduEvent? {
    val type = EventType.parse(getString("type")) ?: return null
    @Suppress("UNCHECKED_CAST")
    val meta = (get("metadata") as? Map<String, Any?>)?.mapValues { it.value?.toString().orEmpty() }.orEmpty()
    return LadduEvent(
        eventId = getString("eventId") ?: id,
        cameraId = getString("cameraId") ?: return null,
        ownerId = getString("ownerId").orEmpty(),
        type = type,
        timestamp = getLong("timestamp") ?: 0L,
        durationMs = getLong("durationMs") ?: 0L,
        confidence = (getDouble("confidence") ?: 0.0).toFloat(),
        ongoing = getBoolean("ongoing") ?: false,
        metadata = meta,
        snapshotRef = getString("snapshotRef"),
        clipRef = getString("clipRef"),
        notify = getBoolean("notify") ?: true,
    )
}

fun CameraStatus.toMap(): Map<String, Any?> = mapOf(
    "monitoring" to monitoring,
    "dogPresent" to dogPresent,
    "moving" to moving,
    "barking" to barking,
    "batteryPct" to batteryPct,
    "charging" to charging,
    "temperatureC" to temperatureC?.toDouble(),
    "thermal" to thermal.name,
    "aiMode" to aiMode.name,
    "aiReady" to aiReady,
    "barkAiReady" to barkAiReady,
)

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.toCamera(): CameraInfo? {
    val ownerId = getString("ownerId") ?: return null
    val s = get("status") as? Map<String, Any?> ?: emptyMap()
    return CameraInfo(
        cameraId = id,
        ownerId = ownerId,
        name = getString("name") ?: "Laddu camera",
        lastSeenMs = getTimestamp("lastSeen")?.toDate()?.time ?: 0L,
        createdAtMs = getTimestamp("createdAt")?.toDate()?.time ?: 0L,
        status = CameraStatus(
            monitoring = s["monitoring"] as? Boolean ?: false,
            dogPresent = s["dogPresent"] as? Boolean ?: false,
            moving = s["moving"] as? Boolean ?: false,
            barking = s["barking"] as? Boolean ?: false,
            batteryPct = (s["batteryPct"] as? Number)?.toInt() ?: -1,
            charging = s["charging"] as? Boolean ?: false,
            temperatureC = (s["temperatureC"] as? Number)?.toFloat(),
            thermal = enumOr(s["thermal"] as? String, ThermalLevel.NORMAL),
            aiMode = enumOr(s["aiMode"] as? String, AiPerformanceMode.BALANCED),
            aiReady = s["aiReady"] as? Boolean ?: false,
            barkAiReady = s["barkAiReady"] as? Boolean ?: false,
        ),
    )
}

fun DocumentSnapshot.toViewerAccess(): ViewerAccess? {
    return ViewerAccess(
        cameraId = getString("cameraId") ?: return null,
        userId = getString("userId") ?: return null,
        email = getString("email").orEmpty(),
        displayName = getString("displayName").orEmpty(),
        createdAtMs = getTimestamp("createdAt")?.toDate()?.time ?: 0L,
    )
}

fun Timestamp.millis(): Long = toDate().time
