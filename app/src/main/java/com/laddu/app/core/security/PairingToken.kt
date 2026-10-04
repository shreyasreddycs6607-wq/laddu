package com.laddu.app.core.security

import com.laddu.app.core.model.PairingSession
import java.security.SecureRandom
import java.util.Base64

/**
 * Pairing tokens: 256 random bits, URL-safe base64, valid for [TTL_MS], redeemable once.
 * The QR code carries ONLY this token - never a password, never a permanent credential.
 * The token is also the Firestore document id of the pairing session, so possessing the QR
 * is the capability; sessions cannot be listed (see firestore.rules).
 */
object PairingToken {
    const val TTL_MS = 5 * 60_000L
    private const val SCHEME = "laddu://pair"
    private val TOKEN_RE = Regex("^[A-Za-z0-9_-]{43}$")

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun toQrPayload(token: String) = "$SCHEME?v=1&t=$token"

    /** Extracts the token from a scanned string, or null if it is not a Laddu pairing QR. */
    fun parseQr(raw: String?): String? {
        if (raw == null || !raw.startsWith("$SCHEME?")) return null
        val token = raw.substringAfter("?").split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "t" }?.get(1)
        return token?.takeIf { TOKEN_RE.matches(it) }
    }
}

sealed interface PairingCheck {
    data object Valid : PairingCheck
    data object Expired : PairingCheck
    data object AlreadyUsed : PairingCheck
    data object Unknown : PairingCheck

    fun message(): String = when (this) {
        Valid -> "OK"
        Expired -> "This QR code has expired. Ask the camera phone to show a new one."
        AlreadyUsed -> "This QR code was already used. Ask the camera phone to show a new one."
        Unknown -> "This QR code is not valid."
    }
}

object PairingValidator {
    fun check(session: PairingSession?, nowMs: Long): PairingCheck = when {
        session == null -> PairingCheck.Unknown
        session.used -> PairingCheck.AlreadyUsed
        nowMs >= session.expiresAtMs -> PairingCheck.Expired
        else -> PairingCheck.Valid
    }
}
