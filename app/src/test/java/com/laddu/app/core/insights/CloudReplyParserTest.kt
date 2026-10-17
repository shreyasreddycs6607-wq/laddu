package com.laddu.app.core.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReplyParserTest {
    private val known = setOf("a", "b")

    @Test fun `valid reply keeps only references the phone knows`() {
        val r = CloudReplyParser.parse(
            mapOf("answer" to " Two barking episodes. ", "references" to listOf("a", "a", "zzz", 5), "facts" to mapOf("eventCount" to 7L, "truncated" to true)),
            known,
        ).getOrThrow()
        assertEquals("Two barking episodes.", r.answer)
        assertEquals(listOf("a"), r.references)
        assertEquals(7, r.eventCount); assertTrue(r.truncated)
    }

    @Test fun `empty, missing or oversized answers are rejected`() {
        assertTrue(CloudReplyParser.parse(null, known).isFailure)
        assertTrue(CloudReplyParser.parse(mapOf("answer" to "  "), known).isFailure)
        assertTrue(CloudReplyParser.parse(mapOf("answer" to 3), known).isFailure)
        assertTrue(CloudReplyParser.parse(mapOf("answer" to "x".repeat(CloudReplyParser.MAX_ANSWER + 1)), known).isFailure)
    }
}
