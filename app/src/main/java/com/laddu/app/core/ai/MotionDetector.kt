package com.laddu.app.core.ai

import androidx.camera.core.ImageProxy

data class MotionResult(
    /** Fraction of sampled cells whose brightness changed noticeably (0..1). */
    val score: Float,
    /** Region of change, upright normalised coordinates; null if none. */
    val box: BoundingBox?,
) {
    companion object { val NONE = MotionResult(0f, null) }
}

/**
 * Very cheap frame-difference on a 48x36 sample of the luminance plane (~1.7k reads per frame).
 * It only answers "did something change?". Whether it was the dog is decided by [DogTracker].
 */
class MotionDetector(
    private val gridW: Int = 48,
    private val gridH: Int = 36,
    private val pixelDelta: Int = 22,
) {
    private var prev: IntArray? = null
    private val cur = IntArray(gridW * gridH)

    fun reset() { prev = null }

    fun process(image: ImageProxy): MotionResult {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = image.width; val h = image.height
        for (gy in 0 until gridH) {
            val y = ((gy + 0.5f) * h / gridH).toInt().coerceIn(0, h - 1)
            for (gx in 0 until gridW) {
                val x = ((gx + 0.5f) * w / gridW).toInt().coerceIn(0, w - 1)
                cur[gy * gridW + gx] = buf.get(y * rowStride + x * pixStride).toInt() and 0xFF
            }
        }
        val p = prev
        if (p == null) { prev = cur.copyOf(); return MotionResult.NONE }

        var changed = 0
        var minX = gridW; var minY = gridH; var maxX = -1; var maxY = -1
        for (gy in 0 until gridH) for (gx in 0 until gridW) {
            val i = gy * gridW + gx
            if (kotlin.math.abs(cur[i] - p[i]) > pixelDelta) {
                changed++
                if (gx < minX) minX = gx; if (gx > maxX) maxX = gx
                if (gy < minY) minY = gy; if (gy > maxY) maxY = gy
            }
        }
        System.arraycopy(cur, 0, p, 0, cur.size)

        val fraction = changed / (gridW * gridH).toFloat()
        // Nearly everything changed at once = auto-exposure / lights switching, not an animal.
        if (fraction > 0.6f || changed == 0) return MotionResult(0f, null)

        val sensorBox = BoundingBox(
            minX / gridW.toFloat(), minY / gridH.toFloat(),
            (maxX + 1) / gridW.toFloat(), (maxY + 1) / gridH.toFloat(),
        )
        return MotionResult(fraction, rotateUpright(sensorBox, image.imageInfo.rotationDegrees))
    }

    companion object {
        /** Maps a box from sensor orientation to upright orientation (rotation is clockwise degrees). */
        fun rotateUpright(b: BoundingBox, degrees: Int): BoundingBox = when ((degrees % 360 + 360) % 360) {
            90 -> BoundingBox(1 - b.bottom, b.left, 1 - b.top, b.right)
            180 -> BoundingBox(1 - b.right, 1 - b.bottom, 1 - b.left, 1 - b.top)
            270 -> BoundingBox(b.top, 1 - b.right, b.bottom, 1 - b.left)
            else -> b
        }
    }
}
