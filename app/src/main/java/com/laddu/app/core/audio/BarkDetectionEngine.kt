package com.laddu.app.core.audio

import android.content.Context
import com.laddu.app.core.ai.ModelLocator
import com.laddu.app.core.ai.ModelNames
import com.laddu.app.core.ai.ModelSource
import com.laddu.app.core.ai.ModelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
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

/**
 * YAMNet through the plain TFLite [Interpreter]. (The Task Audio native library aborts with a Scudo
 * "misaligned pointer" on some Android 12 devices, which no try/catch can survive.)
 */
@Singleton
class TfliteBarkDetectionEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : BarkDetectionEngine {

    private val _status = MutableStateFlow(ModelStatus.NOT_LOADED)
    override val status: StateFlow<ModelStatus> = _status

    private var interpreter: Interpreter? = null
    private var labels: List<SoundClass> = emptyList()
    private var input = FloatArray(0)
    private var output = arrayOf(FloatArray(0))

    @Volatile override var windowSamples: Int = 15_600; private set
    @Volatile override var sampleRate: Int = 16_000; private set

    @Synchronized
    override fun load(): Boolean {
        if (interpreter != null) return true
        val source = ModelLocator.locate(ctx, ModelNames.AUDIO)
        if (source == null) { _status.value = ModelStatus.MODEL_MISSING; return false }
        return try {
            val opts = Interpreter.Options().setNumThreads(1)
            val it = when (source) {
                is ModelSource.InFile -> FileInputStream(source.file).use { f ->
                    Interpreter(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.channel.size()), opts)
                }
                is ModelSource.InAssets -> ctx.assets.openFd(source.path).use { fd ->
                    FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                        .let { Interpreter(it, opts) }
                }
            }
            labels = ctx.assets.open("models/bark_labels.txt").bufferedReader().readLines()
                .filter { it.isNotBlank() }.map { soundClassOf(it.trim()) }
            windowSamples = it.getInputTensor(0).shape().last()
            val classes = it.getOutputTensor(0).shape().last()
            check(classes == labels.size) { "label count $classes != ${labels.size}" }
            input = FloatArray(windowSamples)
            output = arrayOf(FloatArray(classes))
            interpreter = it
            _status.value = ModelStatus.READY
            true
        } catch (t: Throwable) {
            android.util.Log.w("Laddu", "bark model failed to load", t)
            _status.value = ModelStatus.ERROR
            false
        }
    }

    @Synchronized
    override fun classify(window: ShortArray, timestampMs: Long): AudioClassification? {
        val it = interpreter ?: return null
        val n = minOf(window.size, windowSamples)
        for (i in 0 until n) input[i] = window[i] / 32768f
        for (i in n until windowSamples) input[i] = 0f
        it.run(input, output)
        val best = HashMap<SoundClass, Float>()
        output[0].forEachIndexed { i, sc ->
            val cls = labels[i]
            if (sc > 0.05f && sc > (best[cls] ?: 0f)) best[cls] = sc
        }
        return AudioClassification(timestampMs, best)
    }

    @Synchronized
    override fun close() {
        interpreter?.close(); interpreter = null
        _status.value = ModelStatus.NOT_LOADED
    }
}
