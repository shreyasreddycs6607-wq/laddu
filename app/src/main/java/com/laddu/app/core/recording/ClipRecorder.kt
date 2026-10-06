package com.laddu.app.core.recording

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.ImageProxy
import com.google.firebase.storage.StorageMetadata
import com.laddu.app.core.ai.FramePipeline
import com.laddu.app.core.camera.FrameConsumer
import com.laddu.app.core.database.EventDao
import com.laddu.app.core.database.toModel
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.di.AppScope
import com.laddu.app.core.events.EngineOutput
import com.laddu.app.core.events.EventMediaHook
import com.laddu.app.core.events.EventProcessor
import com.laddu.app.core.firebase.FirebaseProvider
import com.laddu.app.core.firebase.LOCAL_OWNER
import com.laddu.app.core.firebase.Paths
import com.laddu.app.core.model.CameraSettings
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import com.laddu.app.core.model.RecordingSettings
import com.laddu.app.core.model.ThermalLevel
import com.laddu.app.core.network.ConnectivityMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Event clips: a small rolling buffer of the last few seconds (JPEG frames, a few fps) is kept in
 * RAM while monitoring. When an event starts, the buffer + the following frames are encoded to an
 * MP4 ("5 s before + 10 s during + 5 s after" by default). Stored locally first; uploaded only if
 * the user enabled cloud upload. Nothing is recorded continuously and nothing leaves the device
 * otherwise.
 */
@Singleton
class ClipRecorder @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val processor: EventProcessor,
    private val dao: EventDao,
    private val connectivity: ConnectivityMonitor,
    private val fb: FirebaseProvider,
    @AppScope private val scope: CoroutineScope,
) : FrameConsumer, EventMediaHook {

    private val clipsDir get() = File(ctx.filesDir, "clips").apply { mkdirs() }
    private val snapsDir get() = File(ctx.filesDir, "snapshots").apply { mkdirs() }

    private val ring = ArrayDeque<ClipFrame>()
    private var lastFrameAt = 0L
    @Volatile var thermal: ThermalLevel = ThermalLevel.NORMAL
    @Volatile private var cam = CameraSettings()
    @Volatile private var rec = RecordingSettings()
    private var watching = false

    private class Capture(val events: MutableList<String>, val frames: MutableList<ClipFrame>, val startMs: Long) {
        var completedAt: Long? = null
        var endAt: Long = Long.MAX_VALUE
        val primaryId: String get() = events.first()
    }
    private var capture: Capture? = null
    private val frameInterval get() = when (thermal) { ThermalLevel.NORMAL -> 250L; ThermalLevel.WARM -> 500L; ThermalLevel.HOT -> 1000L }

    private fun ensureWatching() {
        if (watching) return
        watching = true
        scope.launch { settings.cameraSettings.collect { cam = it } }
        scope.launch { settings.recordingSettings.collect { rec = it; if (!it.enabled) synchronized(this@ClipRecorder) { ring.clear() } } }
    }

    // ------------------------------------------------------------------ frames
    override fun onFrame(image: ImageProxy) {
        ensureWatching()
        if (!rec.enabled) return
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < frameInterval) return
        lastFrameAt = now
        val jpeg = try {
            val bmp = FramePipeline.prepare(image, 480)
            ByteArrayOutputStream(40_000).also { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it); bmp.recycle() }.toByteArray()
        } catch (t: Throwable) { return }
        val frame = ClipFrame(now, jpeg)
        val finished: Capture?
        synchronized(this) {
            ring.addLast(frame)
            val keepMs = cam.clipPreSec * 1000L + 1000
            while (ring.isNotEmpty() && now - ring.first.timestampMs > keepMs) ring.removeFirst()
            val c = capture
            if (c != null) {
                c.frames += frame
                val total = now - c.startMs
                if (c.completedAt == null && total >= cam.clipDuringSec * 1000L) {
                    c.completedAt = now; c.endAt = now + cam.clipPostSec * 1000L
                }
            }
            finished = if (c != null && now >= c.endAt) c.also { capture = null } else null
        }
        finished?.let { scope.launch(Dispatchers.IO) { finalize(it) } }
    }

    // ------------------------------------------------------------------ events
    private fun shouldRecord(t: EventType) = when (t) {
        EventType.BARK, EventType.REPEATED_BARK, EventType.HOWL, EventType.MOVEMENT -> true
        EventType.DOG_PRESENCE -> true
        else -> false
    }

    override suspend fun onEvent(output: EngineOutput): LadduEvent {
        ensureWatching()
        val e = output.event
        if (!rec.enabled || !shouldRecord(e.type)) return e
        val now = System.currentTimeMillis()

        if (output.phase == EngineOutput.Phase.COMPLETED) {
            synchronized(this) {
                val c = capture
                if (c != null && e.eventId in c.events && c.completedAt == null) {
                    c.completedAt = now; c.endAt = now + cam.clipPostSec * 1000L
                }
            }
            return e
        }

        var snapshotPath: String? = null
        synchronized(this) {
            val latest = ring.lastOrNull()
            val c = capture
            if (c != null) {
                c.events += e.eventId
            } else {
                val pre = ring.filter { now - it.timestampMs <= cam.clipPreSec * 1000L }.toMutableList()
                capture = Capture(mutableListOf(e.eventId), pre, now).also {
                    it.endAt = now + (cam.clipDuringSec + cam.clipPostSec) * 1000L + 2000L // safety cap
                }
            }
            if (latest != null) {
                val f = File(snapsDir, "${e.eventId}.jpg")
                runCatching { f.writeBytes(latest.jpeg); snapshotPath = f.absolutePath }
            }
        }
        return e.copy(localSnapshot = snapshotPath ?: e.localSnapshot)
    }

    private suspend fun finalize(c: Capture) {
        val out = File(clipsDir, "${c.primaryId}.mp4")
        val ok = ClipEncoder.encode(c.frames, out)
        c.frames.clear()
        if (!ok) return
        // events sharing one capture share the file
        for (id in c.events) processor.attachMedia(id, localClip = out.absolutePath)
        maybeUpload(c.events)
        cleanup()
    }

    // ------------------------------------------------------------------ cloud (optional) + storage
    private suspend fun maybeUpload(ids: List<String>) {
        val rec = settings.recordingSettings.first() // not the cached copy: it is still the default right after service start
        if (!rec.uploadToCloud || !fb.isConfigured || fb.currentUid == null) return
        if (rec.wifiOnlyUpload && !connectivity.isWifiNow()) return
        if (!connectivity.isOnlineNow()) return
        val uploadedClips = HashMap<String, String>() // events that share one capture share one uploaded MP4
        for (id in ids) {
            val row = dao.get(id)?.toModel() ?: continue
            if (row.ownerId == LOCAL_OWNER || row.ownerId != fb.currentUid) continue
            var updated = row
            if (row.snapshotRef == null && row.localSnapshot != null) {
                upload(File(row.localSnapshot), Paths.snapshotPath(row.cameraId, id), "image/jpeg")?.let { updated = updated.copy(snapshotRef = it) }
            }
            if (row.clipRef == null && row.localClip != null) {
                val ref = uploadedClips[row.localClip]
                    ?: upload(File(row.localClip), Paths.clipPath(row.cameraId, id), "video/mp4")?.also { uploadedClips[row.localClip] = it }
                ref?.let { updated = updated.copy(clipRef = it) }
            }
            if (updated != row) processor.attachMedia(id, clipRef = updated.clipRef, snapshotRef = updated.snapshotRef)
        }
    }

    private suspend fun upload(file: File, path: String, mime: String): String? {
        if (!file.isFile) return null
        return runCatching {
            val ref = fb.storage.reference.child(path)
            ref.putFile(Uri.fromFile(file), StorageMetadata.Builder().setContentType(mime).build()).await()
            path
        }.getOrNull()
    }

    /** Retention + quota. Also retries uploads that were skipped while offline. */
    suspend fun cleanup() = withContext(Dispatchers.IO) {
        val r = settings.recordingSettings.first()
        val now = System.currentTimeMillis()
        val files = allFiles()
        // retention
        files.filter { now - it.lastModified() > r.retentionDays * 86_400_000L }.forEach { it.delete() }
        // quota (oldest first)
        val remaining = allFiles().sortedBy { it.lastModified() }
        var total = remaining.sumOf { it.length() }
        val limit = r.quotaMb * 1024L * 1024L
        for (f in remaining) { if (total <= limit) break; total -= f.length(); f.delete() }
        // retry skipped uploads for the last day
        if (r.uploadToCloud) {
            val camId = settings.cameraId()
            val pending = dao.since(camId, now - 86_400_000L).mapNotNull { it.toModel() }
                .filter { (it.localClip != null && it.clipRef == null) || (it.localSnapshot != null && it.snapshotRef == null) }
            if (pending.isNotEmpty()) maybeUpload(pending.map { it.eventId })
        }
    }

    private fun allFiles(): List<File> =
        (clipsDir.listFiles()?.toList().orEmpty() + snapsDir.listFiles()?.toList().orEmpty()).filter { it.isFile }

    fun usedBytes(): Long = allFiles().sumOf { it.length() }

    fun stop() {
        synchronized(this) { ring.clear(); capture = null }
    }

    /** Delete every local clip and snapshot (Settings -> Privacy). */
    fun deleteAll() {
        stop()
        clipsDir.listFiles()?.forEach { it.delete() }
        snapsDir.listFiles()?.forEach { it.delete() }
    }
}
