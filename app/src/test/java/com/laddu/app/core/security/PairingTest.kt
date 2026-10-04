package com.laddu.app.core.security

import com.laddu.app.core.model.PairingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private fun session(expires: Long, used: Boolean = false) =
        PairingSession("t", "cam", "owner", createdAtMs = 0, expiresAtMs = expires, used = used)

    @Test fun `tokens are 256-bit url-safe and unique`() {
        val tokens = (1..200).map { PairingToken.generate() }
        assertEquals(200, tokens.toSet().size)
        tokens.forEach { assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(it)) } // 32 bytes -> 43 chars
    }

    @Test fun `qr payload round trips and carries nothing but the token`() {
        val t = PairingToken.generate()
        val payload = PairingToken.toQrPayload(t)
        assertEquals(t, PairingToken.parseQr(payload))
        assertTrue(!payload.contains("password", true))
        assertEquals("laddu://pair?v=1&t=$t", payload)
    }

    @Test fun `foreign or malformed qr codes are rejected`() {
        assertNull(PairingToken.parseQr(null))
        assertNull(PairingToken.parseQr("https://evil.example/pair?t=abc"))
        assertNull(PairingToken.parseQr("laddu://pair?v=1&t=short"))
        assertNull(PairingToken.parseQr("laddu://pair?v=1"))
        assertNull(PairingToken.parseQr("WIFI:S:home;P:secret;;"))
        assertNull(PairingToken.parseQr("laddu://pair?v=1&t=" + "a".repeat(44)))
    }

    @Test fun `valid session within its lifetime`() {
        assertEquals(PairingCheck.Valid, PairingValidator.check(session(expires = 1_000), nowMs = 999))
    }

    @Test fun `expired qr is rejected`() {
        assertEquals(PairingCheck.Expired, PairingValidator.check(session(expires = 1_000), nowMs = 1_000))
        assertEquals(PairingCheck.Expired, PairingValidator.check(session(expires = 1_000), nowMs = 5_000))
        assertTrue(PairingCheck.Expired.message().contains("expired"))
    }

    @Test fun `one-time use - a used token is rejected even before it expires`() {
        assertEquals(PairingCheck.AlreadyUsed, PairingValidator.check(session(expires = 1_000, used = true), nowMs = 1))
    }

    @Test fun `unknown token is rejected`() {
        assertEquals(PairingCheck.Unknown, PairingValidator.check(null, 0))
    }

    @Test fun `lifetime is short`() {
        assertTrue(PairingToken.TTL_MS <= 10 * 60_000L)
        assertNotEquals(0L, PairingToken.TTL_MS)
    }
}
