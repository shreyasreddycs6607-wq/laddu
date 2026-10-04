package com.laddu.app.core.ai

/**
 * Minimal IoU tracker. Gives each dog a stable id across inferences and measures how much its
 * bounding box moved recently - this is what turns "pixels changed" into "the DOG moved".
 */
class DogTracker(
    private val matchIou: Float = 0.2f,
    private val maxMissingMs: Long = 4_000,
    private val historyMs: Long = 2_500,
) {
    data class Track(val id: Int, var box: BoundingBox, var lastSeenMs: Long, var confidence: Float) {
        internal val history = ArrayDeque<Pair<Long, BoundingBox>>()
    }

    private var nextId = 1
    private val tracks = mutableListOf<Track>()

    val active: List<Track> get() = tracks

    fun update(detections: List<Detection>, nowMs: Long): List<Track> {
        val unmatched = detections.sortedByDescending { it.confidence }.toMutableList()
        for (t in tracks) {
            val best = unmatched.maxByOrNull { t.box.iou(it.box) } ?: continue
            if (t.box.iou(best.box) >= matchIou || centreClose(t.box, best.box)) {
                t.box = best.box; t.lastSeenMs = nowMs; t.confidence = best.confidence
                t.history.addLast(nowMs to best.box)
                unmatched.remove(best)
            }
        }
        for (d in unmatched) {
            tracks += Track(nextId++, d.box, nowMs, d.confidence).also { it.history.addLast(nowMs to d.box) }
        }
        tracks.removeAll { nowMs - it.lastSeenMs > maxMissingMs }
        for (t in tracks) while (t.history.isNotEmpty() && nowMs - t.history.first().first > historyMs) t.history.removeFirst()
        return tracks.filter { it.lastSeenMs == nowMs }
    }

    private fun centreClose(a: BoundingBox, b: BoundingBox) =
        kotlin.math.hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble()) < 0.15

    fun reset() { tracks.clear() }

    companion object {
        /**
         * Average edge displacement (0..1 of the frame) between the oldest and newest box in the
         * track's recent history. Captures both walking (translation) and rearing/turning (resize).
         */
        fun motionOf(t: Track): Float {
            val first = t.history.firstOrNull()?.second ?: return 0f
            val last = t.history.lastOrNull()?.second ?: return 0f
            if (t.history.size < 2) return 0f
            return (kotlin.math.abs(first.left - last.left) + kotlin.math.abs(first.top - last.top) +
                kotlin.math.abs(first.right - last.right) + kotlin.math.abs(first.bottom - last.bottom)) / 4f
        }
    }
}
