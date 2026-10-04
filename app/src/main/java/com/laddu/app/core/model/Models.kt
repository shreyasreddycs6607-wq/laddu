package com.laddu.app.core.model

/** Role a phone plays. Persisted so the app reopens in the right mode. */
enum class AppMode { CAMERA, VIEWER }

enum class EventCategory { DOG, MOVEMENT, BARKING, SYSTEM }

enum class EventType(val category: EventCategory, val label: String) {
    DOG_PRESENCE(EventCategory.DOG, "Dog presence"),
    MOVEMENT(EventCategory.MOVEMENT, "Movement"),
    BARK(EventCategory.BARKING, "Barking"),
    REPEATED_BARK(EventCategory.BARKING, "Repeated barking"),
    HOWL(EventCategory.BARKING, "Howling"),
    CAMERA_OFFLINE(EventCategory.SYSTEM, "Camera offline"),
    CAMERA_ONLINE(EventCategory.SYSTEM, "Camera online"),
    LOW_BATTERY(EventCategory.SYSTEM, "Low battery");

    companion object {
        fun parse(raw: String?): EventType? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * One logical event. Long-running events (movement, barking, presence) are created once with
 * [ongoing] = true and later completed with the same [eventId] when they end.
 */
data class LadduEvent(
    val eventId: String,
    val cameraId: String,
    val ownerId: String,
    val type: EventType,
    /** Start time, epoch millis. */
    val timestamp: Long,
    val durationMs: Long = 0,
    val confidence: Float = 0f,
    val ongoing: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
    /** Firebase Storage paths, only set when the user enabled cloud upload. */
    val snapshotRef: String? = null,
    val clipRef: String? = null,
    /** Local files on the camera device. Never synced. */
    val localSnapshot: String? = null,
    val localClip: String? = null,
    /** Whether a push notification should be sent for this event. */
    val notify: Boolean = true,
)

data class UserProfile(val uid: String, val email: String, val displayName: String)

data class CameraStatus(
    val monitoring: Boolean = false,
    val dogPresent: Boolean = false,
    val moving: Boolean = false,
    val barking: Boolean = false,
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val temperatureC: Float? = null,
    val thermal: ThermalLevel = ThermalLevel.NORMAL,
    val aiMode: AiPerformanceMode = AiPerformanceMode.BALANCED,
    val aiReady: Boolean = false,
    val barkAiReady: Boolean = false,
    val online: Boolean = true,
)

data class CameraInfo(
    val cameraId: String,
    val ownerId: String,
    val name: String,
    val lastSeenMs: Long = 0,
    val createdAtMs: Long = 0,
    val status: CameraStatus = CameraStatus(),
) {
    /** A camera is online when it is monitoring and its heartbeat is fresh. */
    fun isOnline(nowMs: Long): Boolean = status.monitoring && nowMs - lastSeenMs < ONLINE_WINDOW_MS

    companion object {
        const val ONLINE_WINDOW_MS = 90_000L
        const val HEARTBEAT_MS = 30_000L
    }
}

enum class ViewerRole { VIEWER }

data class ViewerAccess(
    val cameraId: String,
    val userId: String,
    val email: String,
    val displayName: String,
    val role: ViewerRole = ViewerRole.VIEWER,
    val createdAtMs: Long = 0,
)

data class PairingSession(
    val token: String,
    val cameraId: String,
    val ownerId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val used: Boolean = false,
    val usedBy: String? = null,
)

enum class ThermalLevel { NORMAL, WARM, HOT }

enum class AiPerformanceMode(val label: String) {
    LOW("Low (battery & heat friendly)"),
    BALANCED("Balanced"),
    HIGH("High performance")
}

enum class StreamQuality(val label: String, val width: Int, val height: Int, val fps: Int, val maxKbps: Int) {
    LOW("Low", 320, 240, 10, 250),
    MEDIUM("Medium", 640, 480, 15, 800),
    HIGH("High", 1280, 720, 24, 2000);

    fun capped(max: StreamQuality) = if (ordinal > max.ordinal) max else this
}

/** Connection phases of a live session as seen by the viewer. */
enum class LiveState { IDLE, REQUESTING, CONNECTING, LIVE, RECONNECTING, FAILED, ENDED }
