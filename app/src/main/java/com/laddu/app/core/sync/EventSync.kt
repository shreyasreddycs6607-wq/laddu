package com.laddu.app.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.laddu.app.core.database.EventDao
import com.laddu.app.core.database.toModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.laddu.app.core.model.LadduEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where events are uploaded. Faked in unit tests. */
interface EventRemote {
    /** @return true only when the server acknowledged the write. */
    suspend fun upload(event: LadduEvent): Boolean
}

data class SyncResult(val uploaded: Int, val failed: Int, val skippedLocalOnly: Int = 0) {
    val allDone get() = failed == 0
}

/**
 * Offline-first sync: events always land in Room first; this pushes whatever is still
 * `synced = 0` once the Internet is back. Safe to call repeatedly and concurrently-ish
 * (an event modified during upload is NOT marked synced, so the newer version re-uploads).
 */
@Singleton
class EventSyncManager @Inject constructor(
    private val dao: EventDao,
    private val remote: EventRemote,
) {
    // One sync pass at a time: overlapping passes could write an older copy of a row over a newer one.
    private val lock = Mutex()

    suspend fun syncPending(batchSize: Int = 50): SyncResult = lock.withLock {
        var uploaded = 0
        var failed = 0
        var rounds = 0
        while (failed == 0 && rounds++ < MAX_ROUNDS) { // drain a large offline backlog, not just the first batch
            val batch = dao.unsynced(batchSize)
            if (batch.isEmpty()) break
            for (stale in batch) {
                val row = dao.get(stale.eventId) ?: continue // re-read: it may have changed since the batch was read
                if (row.synced) continue
                val model = row.toModel()
                if (model == null) { // unknown type from a future version: do not block the queue
                    dao.markSynced(row.eventId, row.updatedAt)
                    continue
                }
                if (remote.upload(model)) {
                    dao.markSynced(row.eventId, row.updatedAt)
                    uploaded++
                } else {
                    failed++
                    break // network is down / not signed in: stop, retry later in order
                }
            }
        }
        SyncResult(uploaded, failed)
    }

    private companion object { const val MAX_ROUNDS = 40 }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val sync: EventSyncManager,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = sync.syncPending()
        return if (r.allDone) Result.success() else Result.retry()
    }
}

object SyncScheduler {
    private const val NAME = "laddu_event_sync"

    fun enqueue(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
    }
}
