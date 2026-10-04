package com.laddu.app.core.webrtc

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.laddu.app.core.camera.FrameConsumer
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Converts a CameraX YUV_420_888 frame into a WebRTC I420 buffer (handles any row/pixel stride). */
fun ImageProxy.toI420(): JavaI420Buffer {
    val w = width; val h = height
    val cw = (w + 1) / 2; val ch = (h + 1) / 2
    val out = JavaI420Buffer.allocate(w, h)
    copyPlane(planes[0], w, h, out.dataY, out.strideY)
    copyPlane(planes[1], cw, ch, out.dataU, out.strideU)
    copyPlane(planes[2], cw, ch, out.dataV, out.strideV)
    return out
}

private fun copyPlane(src: ImageProxy.PlaneProxy, w: Int, h: Int, dst: ByteBuffer, dstStride: Int) {
    val sb = src.buffer
    val rs = src.rowStride
    val ps = src.pixelStride
    if (ps == 1) {
        val row = ByteArray(w)
        for (y in 0 until h) {
            sb.position(y * rs)
            sb.get(row, 0, w)
            dst.position(y * dstStride)
            dst.put(row, 0, w)
        }
    } else {
        for (y in 0 until h) for (x in 0 until w) dst.put(y * dstStride + x, sb.get(y * rs + x * ps))
    }
}

/** Pushes camera frames into WebRTC while at least one viewer is connected (zero cost otherwise). */
class CameraVideoFeeder(private val observer: CapturerObserver) : FrameConsumer {
    @Volatile var enabled = false
    @Volatile var maxFps = 15
    private var lastAt = 0L

    override fun onFrame(image: ImageProxy) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < 1000L / maxFps - 4) return
        lastAt = now
        val buffer = image.toI420()
        val frame = VideoFrame(buffer, image.imageInfo.rotationDegrees, TimeUnit.MILLISECONDS.toNanos(now))
        observer.onFrameCaptured(frame)
        frame.release()
    }
}
