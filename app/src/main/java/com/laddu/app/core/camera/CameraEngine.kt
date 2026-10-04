package com.laddu.app.core.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.laddu.app.core.model.CameraFacing
import com.laddu.app.core.model.VideoResolution
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.concurrent.futures.await
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** Anything that wants camera frames (AI, motion, WebRTC, clip buffer). Runs on the analysis thread. */
fun interface FrameConsumer {
    /** The image is only valid during this call; copy what you need. Must be fast. */
    fun onFrame(image: ImageProxy)
}

sealed interface CameraEngineState {
    data object Idle : CameraEngineState
    data object Starting : CameraEngineState
    data class Running(val width: Int, val height: Int, val facing: CameraFacing) : CameraEngineState
    data class Error(val message: String) : CameraEngineState
}

/**
 * The single owner of the camera. Everything else (preview, AI, WebRTC) consumes frames from it,
 * so we never open the camera twice. Binds to a [LifecycleOwner] (the monitoring service).
 */
@Singleton
class CameraEngine @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val _state = MutableStateFlow<CameraEngineState>(CameraEngineState.Idle)
    val state: StateFlow<CameraEngineState> = _state

    private val consumers = CopyOnWriteArrayList<FrameConsumer>()
    private val analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "laddu-analysis").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val mainExecutor get() = ContextCompat.getMainExecutor(ctx)

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var stateObserver: Observer<CameraState>? = null
    private var stateOwner: LifecycleOwner? = null
    private var previewSurface: Preview.SurfaceProvider? = null

    @Volatile var lastFrameAtMs: Long = 0L
        private set

    fun addConsumer(c: FrameConsumer) { consumers.addIfAbsent(c) }
    fun removeConsumer(c: FrameConsumer) { consumers.remove(c) }

    /** Show/hide the live preview. Pass null to detach (screen off / UI gone) - frames keep flowing. */
    fun attachPreview(surface: Preview.SurfaceProvider?) {
        previewSurface = surface
        mainExecutor.execute { preview?.setSurfaceProvider(surface) }
    }

    /** Must be called on the main thread. Safe to call again to change camera/resolution. */
    suspend fun start(owner: LifecycleOwner, facing: CameraFacing, resolution: VideoResolution) {
        _state.value = CameraEngineState.Starting
        try {
            val p = provider ?: awaitProvider().also { provider = it }
            unbind()
            val selector = if (facing == CameraFacing.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            if (!p.hasCamera(selector)) throw IllegalStateException("This phone has no ${facing.name.lowercase()} camera")

            val rs = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(resolution.width, resolution.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()

            val newPreview = Preview.Builder().setResolutionSelector(rs).build().also { it.setSurfaceProvider(previewSurface) }
            val newAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(rs)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            newAnalysis.setAnalyzer(analysisExecutor) { image -> dispatch(image) }

            val cam = p.bindToLifecycle(owner, selector, newPreview, newAnalysis)
            preview = newPreview; analysis = newAnalysis; camera = cam
            observeCameraState(owner, cam, facing)
        } catch (t: Throwable) {
            _state.value = CameraEngineState.Error(t.message ?: "Camera failed to start")
            throw t
        }
    }

    private fun observeCameraState(owner: LifecycleOwner, cam: Camera, facing: CameraFacing) {
        removeStateObserver()
        val obs = Observer<CameraState> { s ->
            val err = s.error
            if (err != null) {
                _state.value = CameraEngineState.Error(describe(err))
            } else if (s.type == CameraState.Type.OPEN && _state.value !is CameraEngineState.Running) {
                val res = analysis?.resolutionInfo?.resolution
                _state.value = CameraEngineState.Running(res?.width ?: 0, res?.height ?: 0, facing)
            }
        }
        stateObserver = obs; stateOwner = owner
        cam.cameraInfo.cameraState.observe(owner, obs)
    }

    private fun describe(e: CameraState.StateError): String = when (e.code) {
        CameraState.ERROR_CAMERA_IN_USE -> "Camera is in use by another app"
        CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras in use"
        CameraState.ERROR_CAMERA_DISABLED -> "Camera is disabled by the system or policy"
        CameraState.ERROR_CAMERA_FATAL_ERROR -> "Camera hardware error"
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Camera blocked by Do Not Disturb"
        else -> "Camera error (${e.code})"
    }

    private fun dispatch(image: ImageProxy) {
        try {
            lastFrameAtMs = System.currentTimeMillis()
            for (c in consumers) runCatching { c.onFrame(image) }
        } finally {
            image.close()
        }
    }

    private fun removeStateObserver() {
        val o = stateObserver; val owner = stateOwner
        if (o != null && owner != null) camera?.cameraInfo?.cameraState?.removeObserver(o)
        stateObserver = null; stateOwner = null
    }

    private fun unbind() {
        removeStateObserver()
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        preview = null; analysis = null; camera = null
    }

    /** Main thread. Releases the camera completely. */
    fun stop() {
        unbind()
        _state.value = CameraEngineState.Idle
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = ProcessCameraProvider.getInstance(ctx).await()
}
