package com.laddu.app.core.insights

import com.google.firebase.functions.FirebaseFunctionsException
import com.laddu.app.core.firebase.FirebaseProvider
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** A reply from the `askAssistant` function. The prose is a model interpretation; the counts come from the server's own query. */
data class CloudReply(val answer: String, val references: List<String>, val eventCount: Int, val truncated: Boolean)

object CloudReplyParser {
    const val MAX_ANSWER = 1500

    /** Untrusted input. References the phone does not know are dropped, never opened. */
    fun parse(m: Map<*, *>?, knownIds: Set<String>): Result<CloudReply> = runCatching {
        requireNotNull(m) { "empty reply" }
        val answer = (m["answer"] as? String)?.trim()
        require(!answer.isNullOrEmpty() && answer.length <= MAX_ANSWER) { "bad answer" }
        val refs = (m["references"] as? List<*>).orEmpty().filterIsInstance<String>().distinct().filter { it in knownIds }.take(10)
        val facts = m["facts"] as? Map<*, *>
        CloudReply(answer, refs, (facts?.get("eventCount") as? Number)?.toInt() ?: 0, facts?.get("truncated") as? Boolean ?: false)
    }
}

@Singleton
class CloudAssistantClient @Inject constructor(private val fb: FirebaseProvider) {
    suspend fun ask(cameraId: String, question: String, knownIds: Set<String>): Result<CloudReply> {
        if (!fb.isConfigured || fb.currentUid == null) return Result.failure(IllegalStateException("Sign in to use the cloud assistant."))
        return try {
            val res = withTimeout(40_000) {
                fb.functions.getHttpsCallable("askAssistant").call(hashMapOf("cameraId" to cameraId, "question" to question, "days" to 7)).await()
            }
            CloudReplyParser.parse(res.getData() as? Map<*, *>, knownIds)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(friendlyError(e)))
        }
    }

    private fun friendlyError(e: Exception): String = when ((e as? FirebaseFunctionsException)?.code) {
        FirebaseFunctionsException.Code.FAILED_PRECONDITION -> "The cloud assistant is not set up on the server yet."
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Hourly cloud-assistant limit reached."
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> "This account cannot use the cloud assistant for this camera."
        FirebaseFunctionsException.Code.NOT_FOUND -> "The cloud assistant has not been deployed."
        else -> if (e is kotlinx.coroutines.TimeoutCancellationException) "The cloud assistant took too long." else "The cloud assistant is unavailable."
    }
}
