package com.laddu.app.core.ai

import android.content.Context
import android.graphics.Bitmap
import com.laddu.app.core.model.AiPerformanceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
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

/**
 * On-device detector built on the TensorFlow Lite Task library (LiteRT runtime).
 * Works with any object-detection `.tflite` that carries TFLite metadata (EfficientDet-Lite,
 * SSD MobileNet...). The model must have a "dog" label - COCO-trained models do.
 */
/** Objects are detected down to this score; the owner's dog sensitivity still applies to dogs. */
private const val OBJECT_MIN_CONFIDENCE = 0.35f

@Singleton
class TfliteDogDetectionEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : DogDetectionEngine {

    private val _status = MutableStateFlow(ModelStatus.NOT_LOADED)
    override val status: StateFlow<ModelStatus> = _status

    private var detector: ObjectDetector? = null
    private var loadedThreads = -1
    private var loadedConfidence = -1f

    @Synchronized
    override fun load(threads: Int, minConfidence: Float): Boolean {
        if (detector != null && threads == loadedThreads && minConfidence == loadedConfidence) return true
        detector?.close(); detector = null
        val source = ModelLocator.locate(ctx, ModelNames.DOG)
        if (source == null) { _status.value = ModelStatus.MODEL_MISSING; return false }
        return try {
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(20) // a furnished room has many objects; a dog must not be cut off by the top-N
                .setScoreThreshold(minOf(minConfidence, OBJECT_MIN_CONFIDENCE))
                .setBaseOptions(BaseOptions.builder().setNumThreads(threads).build())
                .build()
            detector = when (source) {
                is ModelSource.InFile -> ObjectDetector.createFromFileAndOptions(source.file, opts)
                is ModelSource.InAssets -> ObjectDetector.createFromFileAndOptions(ctx, source.path, opts)
            }
            loadedThreads = threads; loadedConfidence = minConfidence
            _status.value = ModelStatus.READY
            true
        } catch (t: Throwable) {
            android.util.Log.w("Laddu", "dog model failed to load", t)
            _status.value = ModelStatus.ERROR
            false
        }
    }

    @Synchronized
    override fun detect(bitmap: Bitmap, timestampMs: Long): List<Detection> = detectScene(bitmap, timestampMs).dogs

    @Synchronized
    override fun detectScene(bitmap: Bitmap, timestampMs: Long): Scene {
        val d = detector ?: return Scene(emptyList(), emptyList())
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        val dogs = ArrayList<Detection>()
        val objects = ArrayList<Detection>()
        for (r in d.detect(TensorImage.fromBitmap(bitmap))) {
            val cat = r.categories.firstOrNull() ?: continue
            val b = r.boundingBox
            val det = Detection(
                label = cat.label.lowercase(),
                confidence = cat.score,
                box = BoundingBox(
                    (b.left / w).coerceIn(0f, 1f), (b.top / h).coerceIn(0f, 1f),
                    (b.right / w).coerceIn(0f, 1f), (b.bottom / h).coerceIn(0f, 1f),
                ),
                timestampMs = timestampMs,
            )
            if (det.label == "dog") { if (det.confidence >= loadedConfidence) dogs += det } else objects += det
        }
        return Scene(dogs, objects)
    }

    @Synchronized
    override fun close() {
        detector?.close(); detector = null
        loadedThreads = -1
        _status.value = ModelStatus.NOT_LOADED
    }
}
