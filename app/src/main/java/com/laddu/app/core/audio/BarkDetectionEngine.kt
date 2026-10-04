package com.laddu.app.core.audio

import android.content.Context
import com.laddu.app.core.ai.ModelLocator
import com.laddu.app.core.ai.ModelNames
import com.laddu.app.core.ai.ModelSource
import com.laddu.app.core.ai.ModelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.core.BaseOptions
import javax.inject.Inject
import javax.inject.Singleton

enum class SoundClass { BARK, HOWL, WHINE, HUMAN_VOICE, BACKGROUND_NOISE, UNKNOWN }

/** Result for one ~1 s window: best score per class. */
data class AudioClassification(
    val timestampMs: Long,
    val scores: Map<SoundClass, Float>,
) {
    fun score(c: SoundClass) = scores[c] ?: 0f
    val top: SoundClass? get() = scores.maxByOrNull { it.value }?.key
}

/** Maps AudioSet-style labels (YAMNet) onto Laddu's sound classes. */
fun soundClassOf(label: String): SoundClass {
    val l = label.lowercase()
    return when {
        l == "bark" || l == "bow-wow" || l == "yip" || l == "dog" -> SoundClass.BARK
        l == "howl" || l.startsWith("howl") -> SoundClass.HOWL
        l.startsWith("whimper") || l == "whine" || l == "whimper (dog)" -> SoundClass.WHINE
        l.contains("speech") || l == "conversation" || l == "shout" || l == "yell" || l == "laughter" ||
            l.contains("narration") || l.contains("crying") || l.contains("cry") || l == "child singing" || l == "singing" -> SoundClass.HUMAN_VOICE
        l == "silence" || l == "noise" || l.contains("white noise") || l.contains("static") || l.contains("hum") ||
            l.contains("inside") || l.contains("outside") -> SoundClass.BACKGROUND_NOISE
        else -> SoundClass.UNKNOWN
    }
}

interface BarkDetectionEngine {
    val status: StateFlow<ModelStatus>
    /** Samples a window must contain (model dependent; YAMNet = 15 600 @ 16 kHz). Valid after load(). */
    val windowSamples: Int
    val sampleRate: Int
    fun load(): Boolean
    fun classify(window: ShortArray, timestampMs: Long): AudioClassification?
    fun close()
}

@Singleton
class TfliteBarkDetectionEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : BarkDetectionEngine {

    private val _status = MutableStateFlow(ModelStatus.NOT_LOADED)
    override val status: StateFlow<ModelStatus> = _status

    private var classifier: AudioClassifier? = null
    private var tensor: TensorAudio? = null

    @Volatile override var windowSamples: Int = 15_600; private set
    @Volatile override var sampleRate: Int = 16_000; private set

    @Synchronized
    override fun load(): Boolean {
        if (classifier != null) return true
        val source = ModelLocator.locate(ctx, ModelNames.AUDIO)
        if (source == null) { _status.value = ModelStatus.MODEL_MISSING; return false }
        return try {
            val opts = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setNumThreads(1).build())
                .setMaxResults(15)
                .setScoreThreshold(0.05f)
                .build()
            val c = when (source) {
                is ModelSource.InFile -> AudioClassifier.createFromFileAndOptions(source.file, opts)
                is ModelSource.InAssets -> AudioClassifier.createFromFileAndOptions(ctx, source.path, opts)
            }
            classifier = c
            tensor = c.createInputTensorAudio()
            val fmt = c.requiredTensorAudioFormat
            sampleRate = fmt.sampleRate
            windowSamples = (tensor!!.tensorBuffer.flatSize / fmt.channels)
            _status.value = ModelStatus.READY
            true
        } catch (t: Throwable) {
            _status.value = ModelStatus.ERROR
            false
        }
    }

    @Synchronized
    override fun classify(window: ShortArray, timestampMs: Long): AudioClassification? {
        val c = classifier ?: return null
        val t = tensor ?: return null
        t.load(window, 0, minOf(window.size, windowSamples))
        val best = HashMap<SoundClass, Float>()
        for (cls in c.classify(t)) for (cat in cls.categories) {
            val sc = soundClassOf(cat.label)
            if (cat.score > (best[sc] ?: 0f)) best[sc] = cat.score
        }
        return AudioClassification(timestampMs, best)
    }

    @Synchronized
    override fun close() {
        classifier?.close(); classifier = null; tensor = null
        _status.value = ModelStatus.NOT_LOADED
    }
}
