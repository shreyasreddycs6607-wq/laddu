package com.laddu.app.core.notifications

import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.NotificationPrefs

/** Data carried by an FCM data message (and shown in the notification). */
data class AlertPayload(
    val eventId: String,
    val cameraId: String,
    val type: EventType,
    val title: String,
    val body: String,
    val cameraName: String,
    val timestampMs: Long,
) {
    companion object {
        fun fromData(d: Map<String, String>): AlertPayload? {
            val type = EventType.parse(d["type"]) ?: return null
            val cameraId = d["cameraId"] ?: return null
            return AlertPayload(
                eventId = d["eventId"].orEmpty(),
                cameraId = cameraId,
                type = type,
                title = d["title"] ?: defaultTitle(type),
                body = d["body"] ?: type.label,
                cameraName = d["cameraName"] ?: "Laddu camera",
                timestampMs = d["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis(),
            )
        }

        fun defaultTitle(t: EventType) = when (t) {
            EventType.BARK -> "🔊 Laddu detected barking"
            EventType.REPEATED_BARK -> "⚠️ Repeated barking"
            EventType.HOWL -> "🐺 Laddu heard howling"
            EventType.MOVEMENT -> "🐕 Movement detected"
            EventType.DOG_PRESENCE -> "🐶 Your dog is back"
            EventType.CAMERA_OFFLINE -> "📴 Camera offline"
            EventType.CAMERA_ONLINE -> "✅ Camera online"
            EventType.LOW_BATTERY -> "🔋 Camera battery low"
        }
    }
}

/**
 * Decides whether a notification may be shown: respects the user's per-type switches and keeps a
 * cooldown per camera+type so a barking dog cannot flood the phone. Pure logic (unit-tested).
 */
@javax.inject.Singleton
class NotificationPolicy @javax.inject.Inject constructor() {
    private val lastShown = HashMap<String, Long>()

    fun cooldownMs(type: EventType, prefs: NotificationPrefs): Long = when (type) {
        EventType.REPEATED_BARK -> 10 * 60_000L
        EventType.LOW_BATTERY -> 30 * 60_000L
        EventType.CAMERA_OFFLINE, EventType.CAMERA_ONLINE -> 30_000L
        else -> prefs.cooldownSec * 1000L
    }

    @Synchronized
    fun shouldShow(p: AlertPayload, prefs: NotificationPrefs, nowMs: Long): Boolean {
        if (!prefs.allows(p.type)) return false
        val key = "${p.cameraId}:${p.type}"
        val last = lastShown[key]
        if (last != null && nowMs - last < cooldownMs(p.type, prefs)) return false
        lastShown[key] = nowMs
        return true
    }
}
