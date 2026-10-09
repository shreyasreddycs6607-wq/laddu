package com.laddu.app.core.ai

/**
 * Turns the four outputs of a TFLite SSD "Detection_PostProcess" model into [Detection]s. Pure Kotlin so it can be
 * unit-tested without the native runtime.
 *
 * Output layout (standard for SSD MobileNet metadata models): boxes `[N][ymin, xmin, ymax, xmax]` in 0..1, a class index
 * into the label list, a score, and the number of valid detections.
 */
object SsdDecoder {
    fun decode(
        boxes: Array<FloatArray>,
        classes: FloatArray,
        scores: FloatArray,
        count: Int,
        labels: List<String>,
        minScore: Float,
        timestampMs: Long,
    ): List<Detection> {
        val n = minOf(count, boxes.size, classes.size, scores.size)
        val out = ArrayList<Detection>(n)
        for (i in 0 until n) {
            val score = scores[i]
            if (score < minScore || score.isNaN()) continue
            val label = labels.getOrNull(classes[i].toInt())?.trim()?.lowercase()
            if (label.isNullOrEmpty() || label == "???") continue // unused ids in the COCO map
            val b = boxes[i]
            val left = b[1].coerceIn(0f, 1f); val top = b[0].coerceIn(0f, 1f)
            val right = b[3].coerceIn(0f, 1f); val bottom = b[2].coerceIn(0f, 1f)
            if (right <= left || bottom <= top) continue // degenerate box
            out += Detection(label, score, BoundingBox(left, top, right, bottom), timestampMs)
        }
        return out
    }
}
