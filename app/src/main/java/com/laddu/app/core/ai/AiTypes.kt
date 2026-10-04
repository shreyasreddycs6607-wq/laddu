package com.laddu.app.core.ai

import android.content.Context
import com.laddu.app.core.model.AiPerformanceMode
import com.laddu.app.core.model.ThermalLevel
import java.io.File

/** Box in normalised (0..1) coordinates of the *upright* image. */
data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val area get() = (width * height).coerceAtLeast(0f)

    fun iou(o: BoundingBox): Float {
        val l = maxOf(left, o.left); val t = maxOf(top, o.top)
        val r = minOf(right, o.right); val b = minOf(bottom, o.bottom)
        val inter = (r - l).coerceAtLeast(0f) * (b - t).coerceAtLeast(0f)
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun expanded(f: Float) = BoundingBox(left - width * f, top - height * f, right + width * f, bottom + height * f)
    fun intersects(o: BoundingBox) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** One detected object. */
data class Detection(val label: String, val confidence: Float, val box: BoundingBox, val timestampMs: Long)

enum class ModelStatus { NOT_LOADED, READY, MODEL_MISSING, ERROR }

/** How hard the AI works. Derived from the user's mode and the phone's thermal state. */
data class AiProfile(
    /** Minimum time between two inferences. */
    val intervalMs: Long,
    val threads: Int,
    /** Frames are downscaled so their long side is this many pixels before inference. */
    val inputLongSide: Int,
    /** Time between cheap motion checks. */
    val motionIntervalMs: Long,
)

fun profileFor(mode: AiPerformanceMode, thermal: ThermalLevel): AiProfile {
    val base = when (mode) {
        AiPerformanceMode.LOW -> AiProfile(1500, 1, 320, 300)
        AiPerformanceMode.BALANCED -> AiProfile(700, 2, 320, 150)
        AiPerformanceMode.HIGH -> AiProfile(300, 4, 320, 100)
    }
    return when (thermal) {
        ThermalLevel.NORMAL -> base
        ThermalLevel.WARM -> base.copy(intervalMs = base.intervalMs * 2, threads = maxOf(1, base.threads - 1), motionIntervalMs = base.motionIntervalMs * 2)
        ThermalLevel.HOT -> base.copy(intervalMs = base.intervalMs * 4, threads = 1, motionIntervalMs = base.motionIntervalMs * 4)
    }
}

/** sensitivity 0..1 (higher = more sensitive) -> minimum confidence 0.7 .. 0.3 */
fun confidenceThreshold(sensitivity: Float): Float = 0.7f - 0.4f * sensitivity.coerceIn(0f, 1f)

/**
 * Finds a model file. A model imported to `files/models/<name>` overrides the one bundled in
 * `assets/models/<name>`, which makes every model replaceable without rebuilding the app.
 */
sealed interface ModelSource {
    data class InFile(val file: File) : ModelSource
    data class InAssets(val path: String) : ModelSource
}

object ModelLocator {
    fun locate(ctx: Context, name: String): ModelSource? {
        val f = File(File(ctx.filesDir, "models"), name)
        if (f.isFile && f.length() > 0) return ModelSource.InFile(f)
        val bundled = runCatching { ctx.assets.list("models")?.contains(name) == true }.getOrDefault(false)
        return if (bundled) ModelSource.InAssets("models/$name") else null
    }
}

/** Model file names (replace these files to swap models). */
object ModelNames {
    const val DOG = "dog_detector.tflite"
    const val AUDIO = "bark_classifier.tflite"
}
