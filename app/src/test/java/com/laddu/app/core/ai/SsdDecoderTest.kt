package com.laddu.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdDecoderTest {
    private val labels = listOf("person", "bicycle", "???", "dog", "bottle")

    @Test fun `boxes are converted from ymin-xmin-ymax-xmax and labels looked up`() {
        val out = SsdDecoder.decode(
            arrayOf(floatArrayOf(0.1f, 0.2f, 0.6f, 0.7f)), floatArrayOf(3f), floatArrayOf(0.9f), 1, labels, 0.3f, 42,
        )
        val d = out.single()
        assertEquals("dog", d.label)
        assertEquals(0.2f, d.box.left, 1e-6f); assertEquals(0.1f, d.box.top, 1e-6f)
        assertEquals(0.7f, d.box.right, 1e-6f); assertEquals(0.6f, d.box.bottom, 1e-6f)
        assertEquals(42L, d.timestampMs)
    }

    @Test fun `low scores, unused ids and unknown classes are dropped, never invented`() {
        val boxes = Array(4) { floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f) }
        val out = SsdDecoder.decode(boxes, floatArrayOf(3f, 2f, 9f, 4f), floatArrayOf(0.2f, 0.9f, 0.9f, 0.8f), 4, labels, 0.3f, 0)
        assertEquals(listOf("bottle"), out.map { it.label }) // 0.2 too low, "???" skipped, class 9 has no label
    }

    @Test fun `only count detections are read even if the arrays are longer`() {
        val boxes = Array(3) { floatArrayOf(0f, 0f, 1f, 1f) }
        val out = SsdDecoder.decode(boxes, floatArrayOf(3f, 3f, 3f), floatArrayOf(0.9f, 0.9f, 0.9f), 2, labels, 0.3f, 0)
        assertEquals(2, out.size)
    }

    @Test fun `degenerate and out-of-range boxes are handled safely`() {
        val flat = SsdDecoder.decode(arrayOf(floatArrayOf(0.5f, 0.5f, 0.5f, 0.9f)), floatArrayOf(3f), floatArrayOf(0.9f), 1, labels, 0.3f, 0)
        assertTrue(flat.isEmpty())
        val wide = SsdDecoder.decode(arrayOf(floatArrayOf(-0.2f, -0.1f, 1.4f, 1.3f)), floatArrayOf(3f), floatArrayOf(0.9f), 1, labels, 0.3f, 0).single()
        assertEquals(0f, wide.box.left, 0f); assertEquals(1f, wide.box.right, 0f)
        assertTrue(SsdDecoder.decode(arrayOf(floatArrayOf(0f, 0f, 1f, 1f)), floatArrayOf(3f), floatArrayOf(Float.NaN), 1, labels, 0.3f, 0).isEmpty())
    }

    @Test fun `zero detections gives an empty list`() {
        assertTrue(SsdDecoder.decode(emptyArray(), floatArrayOf(), floatArrayOf(), 0, labels, 0.3f, 0).isEmpty())
    }
}
