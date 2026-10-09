package com.laddu.app.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** What one inference pass saw: dogs (at the dog confidence the user chose) and every other labelled object. */
data class Scene(val dogs: List<Detection>, val objects: List<Detection>)

/** Replaceable dog detector. Implementations must be called from ONE thread at a time. */
interface DogDetectionEngine {
    val status: StateFlow<ModelStatus>

    /** Load (or reload with new options). Safe to call repeatedly; returns true when ready. */
    fun load(threads: Int, minConfidence: Float): Boolean

    /** Detect dogs in an *upright* bitmap. Returns only dog detections (no fake results ever). */
    fun detect(bitmap: Bitmap, timestampMs: Long): List<Detection>

    /**
     * Dogs plus other objects from the same inference (used by hazard detection). Engines that only know dogs keep
     * the default: no objects, never a made-up one.
     */
    fun detectScene(bitmap: Bitmap, timestampMs: Long): Scene = Scene(detect(bitmap, timestampMs), emptyList())

    fun close()
}

/** Objects are detected down to this score; the owner's dog sensitivity still applies to dogs. */
private const val OBJECT_MIN_CONFIDENCE = 0.35f

/**
 * On-device detector for TFLite SSD models with the standard four post-processed outputs (SSD MobileNet, as bundled).
 * It uses the plain TFLite interpreter rather than the Task Vision library, whose native code is not 16 KB-page
 * compatible. The label list is `models/dog_labels.txt` (a file in `files/models/` overrides the bundled one); the model
 * must have a "dog" label - COCO-trained models do.
 */
@Singleton
class TfliteDogDetectionEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : DogDetectionEngine {

    private val _status = MutableStateFlow(ModelStatus.NOT_LOADED)
    override val status: StateFlow<ModelStatus> = _status

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inW = 300
    private var inH = 300
    private var inFloat = false
    private var input: ByteBuffer? = null
    private var pixels = IntArray(0)
    private var maxDet = 10
    private var iBoxes = 0
    private var iClasses = 1
    private var iScores = 2
    private var iCount = 3
    private var loadedThreads = -1
    private var dogMinConfidence = 0.5f

    @Synchronized
    override fun load(threads: Int, minConfidence: Float): Boolean {
        dogMinConfidence = minConfidence // only a filter now: changing it never needs a reload
        if (interpreter != null && threads == loadedThreads) return true
        interpreter?.close(); interpreter = null
        val source = ModelLocator.locate(ctx, ModelNames.DOG)
        if (source == null) { _status.value = ModelStatus.MODEL_MISSING; return false }
        return try {
            labels = readLabels()
            check(labels.any { it.equals("dog", ignoreCase = true) }) { "label list has no \"dog\"" }
            val it = Interpreter(ModelLocator.map(ctx, source), Interpreter.Options().setNumThreads(threads))
            val shape = it.getInputTensor(0).shape() // [1, h, w, 3]
            check(shape.size == 4 && shape[3] == 3) { "unexpected input shape ${shape.toList()}" }
            inH = shape[1]; inW = shape[2]
            inFloat = it.getInputTensor(0).dataType() == DataType.FLOAT32
            input = ByteBuffer.allocateDirect(inW * inH * 3 * (if (inFloat) 4 else 1)).order(ByteOrder.nativeOrder())
            pixels = IntArray(inW * inH)
            mapOutputs(it)
            interpreter = it
            loadedThreads = threads
            _status.value = ModelStatus.READY
            true
        } catch (t: Throwable) {
            Log.w("Laddu", "dog model failed to load", t)
            _status.value = ModelStatus.ERROR
            false
        }
    }

    /** Output order is not guaranteed by the format; use the tensor names (":1" classes, ":2" scores, ":3" count). */
    private fun mapOutputs(it: Interpreter) {
        iBoxes = 0; iClasses = 1; iScores = 2; iCount = 3
        for (i in 0 until it.outputTensorCount) {
            val t = it.getOutputTensor(i)
            val shape = t.shape()
            val name = t.name()
            when {
                shape.size == 3 && shape[2] == 4 -> { iBoxes = i; maxDet = shape[1] }
                name.endsWith(":1") -> iClasses = i
                name.endsWith(":2") -> iScores = i
                name.endsWith(":3") -> iCount = i
            }
        }
    }

    private fun readLabels(): List<String> {
        val override = File(File(ctx.filesDir, "models"), "dog_labels.txt")
        val text = if (override.isFile) override.readText() else ctx.assets.open("models/dog_labels.txt").bufferedReader().use { it.readText() }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Synchronized
    override fun detect(bitmap: Bitmap, timestampMs: Long): List<Detection> = detectScene(bitmap, timestampMs).dogs

    @Synchronized
    override fun detectScene(bitmap: Bitmap, timestampMs: Long): Scene {
        val it = interpreter ?: return Scene(emptyList(), emptyList())
        val buf = input ?: return Scene(emptyList(), emptyList())
        fill(buf, bitmap)

        val boxes = Array(1) { Array(maxDet) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(maxDet) }
        val scores = Array(1) { FloatArray(maxDet) }
        val count = FloatArray(1)
        it.runForMultipleInputsOutputs(
            arrayOf<Any>(buf),
            mapOf(iBoxes to boxes, iClasses to classes, iScores to scores, iCount to count),
        )
        val all = SsdDecoder.decode(boxes[0], classes[0], scores[0], count[0].toInt(), labels, minOf(dogMinConfidence, OBJECT_MIN_CONFIDENCE), timestampMs)
        val (dogs, objects) = all.partition { d -> d.label == "dog" }
        return Scene(dogs.filter { d -> d.confidence >= dogMinConfidence }, objects.filter { o -> o.confidence >= OBJECT_MIN_CONFIDENCE })
    }

    /** Scale to the model's input size and write RGB (uint8, or normalised float for float models). */
    private fun fill(buf: ByteBuffer, bitmap: Bitmap) {
        val scaled = if (bitmap.width == inW && bitmap.height == inH) bitmap else Bitmap.createScaledBitmap(bitmap, inW, inH, true)
        scaled.getPixels(pixels, 0, inW, 0, 0, inW, inH)
        if (scaled !== bitmap) scaled.recycle()
        buf.rewind()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (inFloat) { buf.putFloat((r - 127.5f) / 127.5f); buf.putFloat((g - 127.5f) / 127.5f); buf.putFloat((b - 127.5f) / 127.5f) }
            else { buf.put(r.toByte()); buf.put(g.toByte()); buf.put(b.toByte()) }
        }
        buf.rewind()
    }

    @Synchronized
    override fun close() {
        interpreter?.close(); interpreter = null
        loadedThreads = -1
        _status.value = ModelStatus.NOT_LOADED
    }
}
