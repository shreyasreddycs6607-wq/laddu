package com.laddu.app.core.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.laddu.app.core.database.EventDao
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.events.EventProcessor
import com.laddu.app.core.firebase.FirebaseProvider
import com.laddu.app.core.model.EventCategory
import com.laddu.app.core.model.LadduEvent
import com.laddu.app.core.network.ConnectivityMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls the `analyzeHazardEvidence` Cloud Function. The function (not the app) holds any provider key, checks that the
 * caller owns the camera, and rate-limits per user. Nothing here contains a secret.
 */
@Singleton
class FirebaseCloudInferenceProvider @Inject constructor(private val fb: FirebaseProvider) : DogSafetyInferenceProvider {
    override val id = "firebase-callable"

    override suspend fun analyze(request: AnalysisRequest): Result<CloudAnalysis> = runCatching {
        check(fb.isConfigured && fb.currentUid != null) { "Sign in to use cloud analysis" }
        val payload = hashMapOf(
            "eventId" to request.eventId, "cameraId" to request.cameraId,
            "image" to Base64.encodeToString(request.jpeg, Base64.NO_WRAP),
            "localSummary" to request.localSummary.take(500),
        )
        val res = withTimeout(TIMEOUT_MS) { fb.functions.getHttpsCallable("analyzeHazardEvidence").call(payload).await() }
        CloudAnalysisParser.parse(res.getData() as? Map<*, *>, request.eventId).getOrThrow()
    }

    private companion object { const val TIMEOUT_MS = 25_000L }
}

/**
 * Optional second opinion on a hazard event. It never gates the local alert (that has already been raised); it only
 * adds context when it completes. Limited by: the owner's switch, connectivity, a per-incident cap, and an hourly cap.
 */
@Singleton
class HybridInferenceCoordinator @Inject constructor(
    private val provider: DogSafetyInferenceProvider,
    private val dao: EventDao,
    private val processor: EventProcessor,
    private val settings: SettingsRepository,
    private val connectivity: ConnectivityMonitor,
) {
    private val lock = Mutex()
    private val recent = ArrayDeque<Long>()
    private val analysed = HashSet<String>() // incident ids already sent

    /** Fire-and-forget from the caller's scope. Safe to call for every hazard event. */
    suspend fun maybeAnalyze(e: LadduEvent) {
        if (e.type.category != EventCategory.HAZARD || !e.notify) return
        val policy = settings.safetyPolicy.first()
        if (!policy.enabled || !policy.cloudAnalysis) return
        if (!connectivity.isOnlineNow()) return // evidence stays on the phone; no queue of stale requests
        val incident = e.metadata["incidentId"] ?: e.eventId
        if (!claim(incident)) return

        val jpeg = waitForSnapshot(e.eventId) ?: run { processor.attachMetadata(e.eventId, mapOf("cloud_status" to "no_snapshot")); return }
        val req = AnalysisRequest(e.eventId, e.cameraId, jpeg, summaryOf(e))
        var result = provider.analyze(req)
        if (result.isFailure && retryable(result.exceptionOrNull())) { delay(3_000); result = provider.analyze(req) } // one bounded retry
        result.onSuccess { processor.attachMetadata(e.eventId, it.toMetadata() + ("cloud_status" to "done")) }
            .onFailure {
                Log.w("Laddu", "cloud analysis failed", it)
                processor.attachMetadata(e.eventId, mapOf("cloud_status" to "unavailable"))
            }
    }

    private suspend fun claim(incident: String): Boolean = lock.withLock {
        val now = System.currentTimeMillis()
        while (recent.isNotEmpty() && now - recent.first() > HOUR_MS) recent.removeFirst()
        if (incident in analysed || recent.size >= MAX_PER_HOUR) return false
        analysed += incident
        if (analysed.size > 200) analysed.clear()
        recent.addLast(now)
        true
    }

    private fun retryable(t: Throwable?) = t != null && t !is IllegalStateException && t !is IllegalArgumentException

    private fun summaryOf(e: LadduEvent) =
        "${e.type.label}. ${e.metadata["objectName"] ?: e.metadata["object"] ?: "object"}; local risk ${e.metadata["risk"]}; " +
            "local evidence ${e.metadata["evidence"]}. ${e.metadata["explanation"].orEmpty()}"

    /** The snapshot is written a moment after the event; wait briefly for it. */
    private suspend fun waitForSnapshot(eventId: String): ByteArray? {
        repeat(12) {
            val path = dao.get(eventId)?.localSnapshot
            if (path != null) return withContext(kotlinx.coroutines.Dispatchers.IO) { shrink(File(path)) }
            delay(500)
        }
        return null
    }

    /** At most 640 px on the long side and about 250 KB, enough to see the object without uploading a full-size photo. */
    private fun shrink(f: File): ByteArray? {
        if (!f.isFile) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val scale = 640f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        var q = 75
        var out: ByteArray
        do {
            out = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
            q -= 10
        } while (out.size > MAX_BYTES && q > 25)
        return out
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val MAX_PER_HOUR = 12
        const val MAX_BYTES = 250_000
    }
}
