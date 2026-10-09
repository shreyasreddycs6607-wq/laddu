package com.laddu.app.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAnalysisParserTest {
    private fun good() = mutableMapOf<String, Any?>(
        "eventId" to "ev1", "objects" to listOf("plastic bottle", "dog"), "dogActivity" to "sniffing the floor",
        "suspectedInteraction" to "dog investigating a bottle", "evidence" to "The dog's nose is near a clear bottle.",
        "hazardCategory" to "plastic", "confidence" to 0.6, "recommendedAction" to "Keep watching.", "modelId" to "analyzer-v1",
    )

    @Test fun `a well-formed response is accepted and its metadata cannot collide with local keys`() {
        val a = CloudAnalysisParser.parse(good(), "ev1", nowMs = 5).getOrThrow()
        assertEquals("PLASTIC", a.hazardCategory)
        assertEquals(listOf("plastic bottle", "dog"), a.objects)
        assertTrue(a.toMetadata().keys.all { it.startsWith("cloud_") })
    }

    @Test fun `a response for another event is rejected`() {
        assertTrue(CloudAnalysisParser.parse(good().apply { put("eventId", "other") }, "ev1").isFailure)
    }

    @Test fun `bad confidence, unknown category, missing or oversized fields are rejected`() {
        assertTrue(CloudAnalysisParser.parse(good().apply { put("confidence", 1.5) }, "ev1").isFailure)
        assertTrue(CloudAnalysisParser.parse(good().apply { put("confidence", "high") }, "ev1").isFailure)
        assertTrue(CloudAnalysisParser.parse(good().apply { put("hazardCategory", "LAVA") }, "ev1").isFailure)
        assertTrue(CloudAnalysisParser.parse(good().apply { remove("evidence") }, "ev1").isFailure)
        assertTrue(CloudAnalysisParser.parse(good().apply { put("evidence", "x".repeat(CloudAnalysisParser.MAX_TEXT + 1)) }, "ev1").isFailure)
    }

    @Test fun `null or empty responses fail instead of crashing`() {
        assertTrue(CloudAnalysisParser.parse(null, "ev1").isFailure)
        assertTrue(CloudAnalysisParser.parse(emptyMap<String, Any?>(), "ev1").isFailure)
    }

    @Test fun `object list is capped and blank entries dropped`() {
        val a = CloudAnalysisParser.parse(good().apply { put("objects", List(30) { if (it % 2 == 0) "o$it" else " " }) }, "ev1").getOrThrow()
        assertTrue(a.objects.size <= 12)
        assertFalse(a.objects.any { it.isBlank() })
    }
}
