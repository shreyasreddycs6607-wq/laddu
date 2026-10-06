package com.laddu.app.core.events

import android.content.Context
import com.laddu.app.core.database.EventDao
import com.laddu.app.core.database.toEntity
import com.laddu.app.core.database.toModel
import com.laddu.app.core.di.AppScope
import com.laddu.app.core.model.LadduEvent
import com.laddu.app.core.network.ConnectivityMonitor
import com.laddu.app.core.sync.EventSyncManager
import com.laddu.app.core.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Anything that wants to attach media (snapshot / clip) to events as they start and end. */
interface EventMediaHook {
    suspend fun onEvent(output: EngineOutput): LadduEvent
}

/**
 * Persists engine output: Room FIRST (so nothing is lost offline), then tries to sync to
 * Firestore, falling back to WorkManager which retries when the network returns.
 */
@Singleton
class EventProcessor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: EventDao,
    private val sync: EventSyncManager,
    private val connectivity: ConnectivityMonitor,
    @AppScope private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    @Volatile var mediaHook: EventMediaHook? = null

    suspend fun handle(outputs: List<EngineOutput>) {
        if (outputs.isEmpty()) return
        lock.withLock {
            for (o in outputs) {
                var e = mediaHook?.runCatching { onEvent(o) }?.getOrNull() ?: o.event
                val existing = dao.get(e.eventId)
                if (existing != null) {
                    e = e.copy(
                        localSnapshot = e.localSnapshot ?: existing.localSnapshot,
                        localClip = e.localClip ?: existing.localClip,
                        snapshotRef = e.snapshotRef ?: existing.snapshotRef,
                        clipRef = e.clipRef ?: existing.clipRef,
                    )
                }
                val localOnly = e.metadata[EventEngine.SOURCE_LOCAL] == "true"
                dao.upsert(e.toEntity(synced = localOnly))
            }
        }
        requestSync()
    }

    /** Called when an event's media changed after the fact (clip finished encoding). */
    suspend fun update(event: LadduEvent) {
        lock.withLock {
            val old = dao.get(event.eventId) ?: return
            dao.upsert(event.copy(ongoing = old.ongoing && event.ongoing).toEntity(synced = false))
        }
        requestSync()
    }

    /**
     * Adds media (clip/snapshot paths or cloud refs) to a stored event. Merges onto the row read *under the lock*,
     * so a slow upload can never write back a stale duration/confidence/metadata snapshot of the event.
     */
    suspend fun attachMedia(
        id: String, localClip: String? = null, localSnapshot: String? = null,
        clipRef: String? = null, snapshotRef: String? = null,
    ) {
        val changed = lock.withLock {
            val cur = dao.get(id)?.toModel() ?: return@withLock false
            val merged = cur.copy(
                localClip = localClip ?: cur.localClip, localSnapshot = localSnapshot ?: cur.localSnapshot,
                clipRef = clipRef ?: cur.clipRef, snapshotRef = snapshotRef ?: cur.snapshotRef,
            )
            if (merged == cur) return@withLock false
            dao.upsert(merged.toEntity(synced = false))
            true
        }
        if (changed) requestSync()
    }

    fun requestSync() {
        if (connectivity.isOnlineNow()) {
            scope.launch {
                val r = sync.syncPending()
                if (!r.allDone) SyncScheduler.enqueue(ctx)
            }
        } else {
            SyncScheduler.enqueue(ctx)
        }
    }
}
