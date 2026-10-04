package com.laddu.app.core.recording

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** One buffered camera frame, JPEG-compressed to keep the pre-event buffer small. */
class ClipFrame(val timestampMs: Long, val jpeg: ByteArray)

/**
 * Encodes a list of JPEG frames to an H.264 MP4 using real capture timestamps (so the clip plays
 * at the true speed even though frames were sampled at only a few fps). Video only.
 */
object ClipEncoder {
    private const val TIMEOUT_US = 10_000L

    fun encode(frames: List<ClipFrame>, out: File, bitRate: Int = 700_000): Boolean {
        if (frames.size < 2) return false
        val first = BitmapFactory.decodeByteArray(frames[0].jpeg, 0, frames[0].jpeg.size) ?: return false
        val w = first.width and 1.inv(); val h = first.height and 1.inv()
        first.recycle()
        if (w <= 0 || h <= 0) return false

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 5)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
            }
            out.parentFile?.mkdirs()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            fun drain(endOfStream: Boolean) {
                val c = codec!!
                while (true) {
                    val idx = c.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer!!.addTrack(c.outputFormat); muxer!!.start(); muxerStarted = true
                        }
                        idx >= 0 -> {
                            val buf = c.getOutputBuffer(idx)
                            if (buf != null && info.size > 0 && muxerStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                muxer!!.writeSampleData(track, buf, info)
                            }
                            c.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            val t0 = frames[0].timestampMs
            var lastUs = -1L
            for (f in frames) {
                val bmp = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size) ?: continue
                val scaled = if (bmp.width != w || bmp.height != h) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
                var inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                var guard = 0
                while (inIdx < 0 && guard++ < 50) { drain(false); inIdx = codec.dequeueInputBuffer(TIMEOUT_US) }
                if (inIdx >= 0) {
                    val image = codec.getInputImage(inIdx)
                    if (image != null) {
                        fillImage(image, scaled, w, h)
                        var us = (f.timestampMs - t0) * 1000
                        if (us <= lastUs) us = lastUs + 1000
                        lastUs = us
                        codec.queueInputBuffer(inIdx, 0, w * h * 3 / 2, us, 0)
                    }
                }
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
                drain(false)
            }
            val eos = codec.dequeueInputBuffer(TIMEOUT_US)
            if (eos >= 0) codec.queueInputBuffer(eos, 0, 0, lastUs + 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            muxerStarted
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }; runCatching { muxer?.release() }
            if (!out.exists() || out.length() == 0L) out.delete()
        }
    }

    /** ARGB bitmap -> the encoder's YUV420 planes (BT.601), honouring the plane strides. */
    private fun fillImage(image: android.media.Image, bmp: Bitmap, w: Int, h: Int) {
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = argb[y * w + x]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                yBuf.put(y * yP.rowStride + x * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte())
                if (y and 1 == 0 && x and 1 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    uBuf.put((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride, u)
                    vBuf.put((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride, v)
                }
            }
        }
    }
}
