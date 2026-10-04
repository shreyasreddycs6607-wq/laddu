package com.laddu.app.core.model

import org.json.JSONObject

enum class CameraFacing { BACK, FRONT }

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    VGA("480p (older phones)", 640, 480),
    HD("720p", 1280, 720),
    FULL_HD("1080p", 1920, 1080)
}

/** Settings that drive what the camera phone detects. Remotely editable by viewers. */
data class CameraSettings(
    val facing: CameraFacing = CameraFacing.BACK,
    val resolution: VideoResolution = VideoResolution.VGA,
    val dogDetection: Boolean = true,
    val movementDetection: Boolean = true,
    val barkDetection: Boolean = true,
    val howlDetection: Boolean = true,
    val aiMode: AiPerformanceMode = AiPerformanceMode.BALANCED,
    /** 0..1, higher = more sensitive (lower confidence threshold). */
    val dogSensitivity: Float = 0.5f,
    val barkSensitivity: Float = 0.5f,
    val barkCount: Int = 3,
    val barkWindowSec: Int = 30,
    val clipPreSec: Int = 5,
    val clipDuringSec: Int = 10,
    val clipPostSec: Int = 5,
    val updatedAtMs: Long = 0,
) {
    fun toJson() = JSONObject().apply {
        put("facing", facing.name); put("resolution", resolution.name)
        put("dogDetection", dogDetection); put("movementDetection", movementDetection)
        put("barkDetection", barkDetection); put("howlDetection", howlDetection)
        put("aiMode", aiMode.name); put("dogSensitivity", dogSensitivity.toDouble())
        put("barkSensitivity", barkSensitivity.toDouble()); put("barkCount", barkCount)
        put("barkWindowSec", barkWindowSec); put("clipPreSec", clipPreSec)
        put("clipDuringSec", clipDuringSec); put("clipPostSec", clipPostSec)
        put("updatedAtMs", updatedAtMs)
    }

    fun toMap(): Map<String, Any> = toJson().let { j -> j.keys().asSequence().associateWith { j.get(it) } }

    companion object {
        fun fromJson(j: JSONObject?): CameraSettings {
            val d = CameraSettings()
            if (j == null) return d
            return CameraSettings(
                facing = enumOr(j.optString("facing"), d.facing),
                resolution = enumOr(j.optString("resolution"), d.resolution),
                dogDetection = j.optBoolean("dogDetection", d.dogDetection),
                movementDetection = j.optBoolean("movementDetection", d.movementDetection),
                barkDetection = j.optBoolean("barkDetection", d.barkDetection),
                howlDetection = j.optBoolean("howlDetection", d.howlDetection),
                aiMode = enumOr(j.optString("aiMode"), d.aiMode),
                dogSensitivity = j.optDouble("dogSensitivity", d.dogSensitivity.toDouble()).toFloat().coerceIn(0f, 1f),
                barkSensitivity = j.optDouble("barkSensitivity", d.barkSensitivity.toDouble()).toFloat().coerceIn(0f, 1f),
                barkCount = j.optInt("barkCount", d.barkCount).coerceIn(1, 20),
                barkWindowSec = j.optInt("barkWindowSec", d.barkWindowSec).coerceIn(5, 300),
                clipPreSec = j.optInt("clipPreSec", d.clipPreSec).coerceIn(0, 10),
                clipDuringSec = j.optInt("clipDuringSec", d.clipDuringSec).coerceIn(2, 60),
                clipPostSec = j.optInt("clipPostSec", d.clipPostSec).coerceIn(0, 15),
                updatedAtMs = j.optLong("updatedAtMs", 0),
            )
        }

        fun fromMap(m: Map<String, Any?>?): CameraSettings =
            fromJson(m?.let { JSONObject(it.filterValues { v -> v != null }) })
    }
}

data class NotificationPrefs(
    val barking: Boolean = true,
    val repeatedBarking: Boolean = true,
    val movement: Boolean = true,
    val dogReturned: Boolean = true,
    val cameraOffline: Boolean = true,
    val cameraOnline: Boolean = true,
    val lowBattery: Boolean = true,
    /** Minimum seconds between two notifications of the same type. */
    val cooldownSec: Int = 120,
) {
    fun toJson() = JSONObject().apply {
        put("barking", barking); put("repeatedBarking", repeatedBarking); put("movement", movement)
        put("dogReturned", dogReturned); put("cameraOffline", cameraOffline)
        put("cameraOnline", cameraOnline); put("lowBattery", lowBattery); put("cooldownSec", cooldownSec)
    }

    fun toMap(): Map<String, Any> = toJson().let { j -> j.keys().asSequence().associateWith { j.get(it) } }

    fun allows(type: EventType): Boolean = when (type) {
        EventType.BARK, EventType.HOWL -> barking
        EventType.REPEATED_BARK -> repeatedBarking
        EventType.MOVEMENT -> movement
        EventType.DOG_PRESENCE -> dogReturned
        EventType.CAMERA_OFFLINE -> cameraOffline
        EventType.CAMERA_ONLINE -> cameraOnline
        EventType.LOW_BATTERY -> lowBattery
    }

    companion object {
        fun fromJson(j: JSONObject?): NotificationPrefs {
            val d = NotificationPrefs()
            if (j == null) return d
            return NotificationPrefs(
                barking = j.optBoolean("barking", d.barking),
                repeatedBarking = j.optBoolean("repeatedBarking", d.repeatedBarking),
                movement = j.optBoolean("movement", d.movement),
                dogReturned = j.optBoolean("dogReturned", d.dogReturned),
                cameraOffline = j.optBoolean("cameraOffline", d.cameraOffline),
                cameraOnline = j.optBoolean("cameraOnline", d.cameraOnline),
                lowBattery = j.optBoolean("lowBattery", d.lowBattery),
                cooldownSec = j.optInt("cooldownSec", d.cooldownSec).coerceIn(0, 3600),
            )
        }
    }
}

data class RecordingSettings(
    val enabled: Boolean = true,
    val uploadToCloud: Boolean = false,
    val wifiOnlyUpload: Boolean = true,
    val quotaMb: Int = 500,
    val retentionDays: Int = 7,
) {
    fun toJson() = JSONObject().apply {
        put("enabled", enabled); put("uploadToCloud", uploadToCloud); put("wifiOnlyUpload", wifiOnlyUpload)
        put("quotaMb", quotaMb); put("retentionDays", retentionDays)
    }

    companion object {
        fun fromJson(j: JSONObject?): RecordingSettings {
            val d = RecordingSettings()
            if (j == null) return d
            return RecordingSettings(
                enabled = j.optBoolean("enabled", d.enabled),
                uploadToCloud = j.optBoolean("uploadToCloud", d.uploadToCloud),
                wifiOnlyUpload = j.optBoolean("wifiOnlyUpload", d.wifiOnlyUpload),
                quotaMb = j.optInt("quotaMb", d.quotaMb).coerceIn(50, 10_000),
                retentionDays = j.optInt("retentionDays", d.retentionDays).coerceIn(1, 90),
            )
        }
    }
}

data class NetworkSettings(
    val defaultQuality: StreamQuality = StreamQuality.MEDIUM,
    /** Viewer: only stream on Wi-Fi. */
    val streamOnWifiOnly: Boolean = false,
    /** Force relayed (TURN) connections only: hides IPs from the peer, costs relay bandwidth. */
    val forceRelay: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("defaultQuality", defaultQuality.name); put("streamOnWifiOnly", streamOnWifiOnly)
        put("forceRelay", forceRelay)
    }

    companion object {
        fun fromJson(j: JSONObject?): NetworkSettings {
            val d = NetworkSettings()
            if (j == null) return d
            return NetworkSettings(
                defaultQuality = enumOr(j.optString("defaultQuality"), d.defaultQuality),
                streamOnWifiOnly = j.optBoolean("streamOnWifiOnly", d.streamOnWifiOnly),
                forceRelay = j.optBoolean("forceRelay", d.forceRelay),
            )
        }
    }
}

internal inline fun <reified E : Enum<E>> enumOr(raw: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == raw } ?: default
