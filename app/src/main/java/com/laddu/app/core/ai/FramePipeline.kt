package com.laddu.app.core.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.laddu.app.core.camera.FrameConsumer
import com.laddu.app.core.events.EngineInput
import com.laddu.app.core.model.CameraSettings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX frame -> cheap motion check -> (if due / motion) preprocessing -> AI inference ->
 * confidence filter -> tracking -> movement confirmation -> [EngineInput]s for the EventEngine.
 *
 * Only ONE inference runs at a time and frames that arrive meanwhile are dropped, so a slow phone
 * simply analyses fewer frames instead of falling behind or leaking memory.
 */
class FramePipeline(
    private val dog: DogDetectionEngine,
    private val emit: (EngineInput) -> Unit,
    private val onDogBox: (BoundingBox?) -> Unit = {},
) : FrameConsumer {

    @Volatile var settings: CameraSettings = CameraSettings()
    @Volatile var profile: AiProfile = profileFor(settings.aiMode, com.laddu.app.core.model.ThermalLevel.NORMAL)

    private val motion = MotionDetector()
    private val tracker = DogTracker()
    private val busy = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "laddu-inference").apply { priority = Thread.NORM_PRIORITY - 1 } }

    private var lastMotionAt = 0L
    private var lastMotion = MotionResult.NONE
    private var lastInferenceAt = 0L
    @Volatile private var closed = false

    @Volatile var inferenceCount = 0L
        private set
    @Volatile var lastInferenceMs = 0L
        private set

    override fun onFrame(image: ImageProxy) {
        if (closed) return
        val s = settings; val p = profile
        if (!s.dogDetection && !s.movementDetection) return
        val now = System.currentTimeMillis()

        if (s.movementDetection && now - lastMotionAt >= p.motionIntervalMs) {
            lastMotionAt = now
            lastMotion = motion.process(image)
        }
        val motionFresh = now - lastMotionAt < 1500
        val possible = motionFresh && lastMotion.score >= MOTION_THRESHOLD
        val due = now - lastInferenceAt >= p.intervalMs

        if (!s.dogDetection) {
            // Dog AI off: fall back to plain motion (not dog-specific) at the AI cadence.
            if (due) { lastInferenceAt = now; emit(EngineInput.MovementSample(now, possible, lastMotion.score)) }
            return
        }
        val early = possible && now - lastInferenceAt >= maxOf(250L, p.intervalMs / 2)
        if (!(due || early)) return
        if (!busy.compareAndSet(false, true)) return
        lastInferenceAt = now

        val bitmap = try { prepare(image, p.inputLongSide) } catch (t: Throwable) { busy.set(false); return }
        val motionSnapshot = lastMotion
        executor.execute {
            try {
                if (!closed) infer(bitmap, now, possible, motionSnapshot, s)
            } catch (_: Throwable) {
                // never let one bad frame kill monitoring
            } finally {
                bitmap.recycle()
                busy.set(false)
            }
        }
    }

    private fun infer(bmp: Bitmap, now: Long, possibleMotion: Boolean, motionResult: MotionResult, s: CameraSettings) {
        val t0 = System.nanoTime()
        val detections = dog.detect(bmp, now)
        lastInferenceMs = (System.nanoTime() - t0) / 1_000_000
        inferenceCount++
        val tracks = tracker.update(detections, now)

        if (detections.isNotEmpty()) {
            emit(EngineInput.DogSeen(now, detections.maxOf { it.confidence }))
            onDogBox(detections.maxBy { it.confidence }.box)
        } else {
            emit(EngineInput.DogAbsent(now))
            onDogBox(null)
        }

        if (s.movementDetection) {
            var confirmed = false
            var amount = 0f
            for (t in tracks) {
                val boxMotion = DogTracker.motionOf(t)
                val overlap = motionResult.box?.let { t.box.expanded(0.15f).intersects(it) } == true
                if ((possibleMotion && overlap) || boxMotion >= BOX_MOTION_THRESHOLD) {
                    confirmed = true
                    amount = maxOf(amount, boxMotion, motionResult.score)
                }
            }
            emit(EngineInput.MovementSample(now, confirmed, amount))
        }
    }

    fun reset() { motion.reset(); tracker.reset() }

    fun close() {
        closed = true
        executor.shutdown()
    }

    companion object {
        const val MOTION_THRESHOLD = 0.012f
        const val BOX_MOTION_THRESHOLD = 0.03f

        /** YUV image -> upright bitmap with its long side <= [longSide]. */
        fun prepare(image: ImageProxy, longSide: Int): Bitmap {
            val full = image.toBitmap()
            val scale = (longSide / maxOf(full.width, full.height).toFloat()).coerceAtMost(1f)
            val m = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                postScale(scale, scale)
            }
            val out = Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true)
            if (out !== full) full.recycle()
            return out
        }
    }
}
