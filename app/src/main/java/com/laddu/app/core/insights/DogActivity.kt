package com.laddu.app.core.insights

/**
 * What the dog appears to be doing from how far its box moved in the last few seconds. These are *observations of
 * movement*, not interpretations: "playing" cannot be told apart from running or walking with one camera and no pose
 * model, so it is deliberately not offered.
 */
enum class DogActivity(val label: String) {
    OUT_OF_VIEW("Out of view"),
    RESTING("Resting-like"),
    WALKING("Walking-like"),
    RUNNING("Running-like"),
}

object ActivityClassifier {
    /** [motion] is the average box-edge displacement over the tracker's ~2.5 s window, as a fraction of the frame. */
    const val RESTING_BELOW = 0.03f
    const val RUNNING_FROM = 0.20f

    fun classify(dogInView: Boolean, motion: Float): DogActivity = when {
        !dogInView -> DogActivity.OUT_OF_VIEW
        motion < RESTING_BELOW -> DogActivity.RESTING
        motion < RUNNING_FROM -> DogActivity.WALKING
        else -> DogActivity.RUNNING
    }

    /** Safe parse for a value that came from the network. */
    fun parse(raw: String?): DogActivity = DogActivity.entries.firstOrNull { it.name == raw } ?: DogActivity.OUT_OF_VIEW
}
