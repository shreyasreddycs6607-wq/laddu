package com.laddu.app.core.audio

import com.laddu.app.core.events.EngineInput
import com.laddu.app.core.model.CameraSettings

/** Minimum score for a window to count as a bark, from the user's 0..1 sensitivity: 0.60 .. 0.20. */
fun barkThreshold(sensitivity: Float): Float = 0.6f - 0.4f * sensitivity.coerceIn(0f, 1f)

/**
 * Microphone window -> classifier -> bark/howl confidence -> [EngineInput]. The "3 barks within
 * 30 s" rule itself lives in the EventEngine so it stays unit-testable.
 */
class BarkPipeline(
    private val engine: BarkDetectionEngine,
    private val emit: (EngineInput) -> Unit,
    private val onLevel: (AudioClassification) -> Unit = {},
) {
    @Volatile var settings: CameraSettings = CameraSettings()

    /** Called for each full window (on the audio thread). */
    fun onWindow(window: ShortArray, nowMs: Long) {
        val s = settings
        if (!s.barkDetection && !s.howlDetection) return
        val result = engine.classify(window, nowMs) ?: return
        onLevel(result)
        val bark = result.score(SoundClass.BARK)
        val howl = result.score(SoundClass.HOWL)
        val human = result.score(SoundClass.HUMAN_VOICE)
        val thr = barkThreshold(s.barkSensitivity)

        // A TV or a person talking should not become a "bark": the dog class must clearly win.
        if (s.barkDetection && bark >= thr && bark >= human) emit(EngineInput.BarkSample(nowMs, bark))
        if (s.howlDetection && howl >= thr + 0.1f && howl >= human) emit(EngineInput.HowlSample(nowMs, howl))
    }
}
