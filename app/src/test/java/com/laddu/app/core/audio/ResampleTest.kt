package com.laddu.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ResampleTest {
    private fun pcm(vararg v: Int): ByteArray = ByteArray(v.size * 2).also { b ->
        v.forEachIndexed { i, s -> b[i * 2] = (s and 0xFF).toByte(); b[i * 2 + 1] = (s shr 8).toByte() }
    }

    @Test fun `48k to 16k averages blocks of three instead of point sampling`() {
        val out = resampleToMono(pcm(0, 300, 600, 900, 900, 900), 48_000, 1, 16_000)
        assertEquals(listOf<Short>(300, 900), out.toList())
    }

    @Test fun `same rate is passed through and stereo is mixed to mono`() {
        val out = resampleToMono(pcm(100, 300, -100, -300), 16_000, 2, 16_000)
        assertEquals(listOf<Short>(200, -200), out.toList())
    }
}
