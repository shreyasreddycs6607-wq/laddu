package com.laddu.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Collects arbitrary-sized PCM chunks into fixed windows. Pure logic, unit-tested. */
class AudioWindower(private val windowSize: () -> Int, private val onWindow: (ShortArray) -> Unit) {
    private var buf = ShortArray(0)
    private var fill = 0

    @Synchronized
    fun push(data: ShortArray, len: Int = data.size) {
        val size = windowSize()
        if (buf.size != size) { buf = ShortArray(size); fill = 0 }
        var off = 0
        while (off < len) {
            val n = minOf(size - fill, len - off)
            System.arraycopy(data, off, buf, fill, n)
            fill += n; off += n
            if (fill == size) { onWindow(buf.copyOf()); fill = 0 }
        }
    }

    @Synchronized fun reset() { fill = 0 }
}

/** Linear down-sampler used when WebRTC hands us 48 kHz / stereo audio. */
fun resampleToMono(src: ByteArray, srcRate: Int, channels: Int, dstRate: Int): ShortArray {
    val frames = src.size / 2 / channels
    val mono = ShortArray(frames)
    for (i in 0 until frames) {
        var sum = 0
        for (c in 0 until channels) {
            val idx = (i * channels + c) * 2
            sum += ((src[idx + 1].toInt() shl 8) or (src[idx].toInt() and 0xFF)).toShort().toInt()
        }
        mono[i] = (sum / channels).toShort()
    }
    if (srcRate == dstRate) return mono
    if (srcRate % dstRate == 0) { // integer ratio (48 kHz -> 16 kHz): average blocks, a cheap low-pass that stops aliasing
        val k = srcRate / dstRate
        val avg = ShortArray(frames / k)
        for (i in avg.indices) { var sum = 0; for (j in 0 until k) sum += mono[i * k + j]; avg[i] = (sum / k).toShort() }
        return avg
    }
    val outLen = (frames.toLong() * dstRate / srcRate).toInt()
    val out = ShortArray(outLen)
    val ratio = srcRate.toDouble() / dstRate
    for (i in 0 until outLen) {
        val pos = i * ratio
        val i0 = pos.toInt().coerceAtMost(frames - 1)
        val i1 = (i0 + 1).coerceAtMost(frames - 1)
        val f = pos - i0
        out[i] = (mono[i0] * (1 - f) + mono[i1] * f).toInt().toShort()
    }
    return out
}

/**
 * Microphone capture on a background coroutine. Owns the AudioRecord and always releases it,
 * even on cancellation (no mic leaks). Call [pause] while another component (WebRTC) owns the mic.
 */
class AudioCapture(private val scope: CoroutineScope, private val sampleRate: Int = 16_000) {
    private var job: Job? = null
    @Volatile var error: String? = null
        private set
    @Volatile var active: Boolean = false
        private set

    @SuppressLint("MissingPermission") // caller verifies RECORD_AUDIO before start()
    fun start(onSamples: (ShortArray, Int) -> Unit) {
        if (job?.isActive == true) return
        error = null
        job = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) { error = "Microphone not supported"; return@launch }
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sampleRate), // ~0.5 s buffer
                )
            } catch (t: Throwable) { error = t.message ?: "Microphone unavailable"; return@launch }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release(); error = "Microphone is in use or unavailable"; return@launch
            }
            val chunk = ShortArray(sampleRate / 10) // 100 ms
            try {
                rec.startRecording()
                active = true
                while (isActive) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n > 0) onSamples(chunk, n)
                    else if (n < 0) { error = "Microphone read error ($n)"; break }
                }
            } finally {
                active = false
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    suspend fun stopAndJoin() { job?.cancel(); job?.join(); job = null; active = false }
    fun stop() { job?.cancel(); job = null }
    val running get() = job?.isActive == true
}
