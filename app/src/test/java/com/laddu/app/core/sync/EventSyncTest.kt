package com.laddu.app.core.sync

import com.laddu.app.core.database.EventDao
import com.laddu.app.core.database.EventEntity
import com.laddu.app.core.database.toEntity
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory DAO with the same semantics as the Room one (incl. the updatedAt guard in markSynced). */
class FakeEventDao : EventDao {
    val rows = linkedMapOf<String, EventEntity>()
    private val pending = MutableStateFlow(0)
    private fun refresh() { pending.value = rows.values.count { !it.synced } }

    override suspend fun upsert(e: EventEntity) { rows[e.eventId] = e; refresh() }
    override suspend fun get(id: String) = rows[id]
    override suspend fun unsynced(limit: Int) = rows.values.filter { !it.synced }.sortedBy { it.timestamp }.take(limit)
    override fun pendingCount(): Flow<Int> = pending
    override suspend fun markSynced(id: String, updatedAt: Long): Int {
        val r = rows[id] ?: return 0
        if (r.updatedAt != updatedAt) return 0
        rows[id] = r.copy(synced = true); refresh(); return 1
    }
    override fun observeRecent(cameraId: String, limit: Int): Flow<List<EventEntity>> =
        pending.map { rows.values.filter { it.cameraId == cameraId }.sortedByDescending { it.timestamp }.take(limit) }
    override suspend fun since(cameraId: String, since: Long) = rows.values.filter { it.cameraId == cameraId && it.timestamp >= since }
    override suspend fun deleteSyncedBefore(before: Long): Int {
        val del = rows.values.filter { it.synced && it.timestamp < before }.map { it.eventId }
        del.forEach { rows.remove(it) }; refresh(); return del.size
    }
    override suspend fun closeOngoing(now: Long) = 0
    override suspend fun clear() { rows.clear(); refresh() }
}

class FakeRemote(var online: Boolean = true) : EventRemote {
    val uploaded = mutableListOf<String>()
    var onUpload: (suspend (LadduEvent) -> Unit)? = null
    override suspend fun upload(event: LadduEvent): Boolean {
        if (!online) return false
        onUpload?.invoke(event)
        uploaded += event.eventId
        return true
    }
}

class EventSyncTest {
    private fun ev(id: String, ts: Long) = LadduEvent(id, "cam", "owner", EventType.BARK, ts)
    private fun runTest(block: suspend () -> Unit) = runBlocking { block() }

    @Test fun `events written offline are queued and uploaded once the network returns`() = runTest {
        val dao = FakeEventDao(); val remote = FakeRemote(online = false)
        val sync = EventSyncManager(dao, remote)
        listOf(ev("a", 1), ev("b", 2), ev("c", 3)).forEach { dao.upsert(it.toEntity()) }

        val offline = sync.syncPending()
        assertEquals(0, offline.uploaded); assertEquals(1, offline.failed) // stops at first failure, keeps order
        assertEquals(3, dao.rows.values.count { !it.synced })

        remote.online = true
        val back = sync.syncPending()
        assertEquals(3, back.uploaded); assertTrue(back.allDone)
        assertEquals(listOf("a", "b", "c"), remote.uploaded) // chronological
        assertTrue(dao.rows.values.all { it.synced })
    }

    @Test fun `already synced events are not uploaded again`() = runTest {
        val dao = FakeEventDao(); val remote = FakeRemote()
        val sync = EventSyncManager(dao, remote)
        dao.upsert(ev("a", 1).toEntity())
        sync.syncPending(); sync.syncPending()
        assertEquals(listOf("a"), remote.uploaded)
    }

    @Test fun `an event modified while uploading is NOT marked synced by the stale upload and is re-sent`() = runTest {
        val dao = FakeEventDao(); val remote = FakeRemote()
        val sync = EventSyncManager(dao, remote)
        dao.upsert(ev("a", 1).copy(durationMs = 0).toEntity(updatedAt = 100))
        // while the (slow) upload is in flight, the event is completed locally
        remote.onUpload = { dao.upsert(ev("a", 1).copy(durationMs = 5000).toEntity(updatedAt = 200)) }
        sync.syncPending()
        // the stale v1 upload must not mark v2 synced; the same pass re-reads the row and sends the completed v2
        assertTrue(dao.rows.getValue("a").synced)
        assertEquals(2, remote.uploaded.size) // v1 then the completed v2
        assertEquals(5000L, dao.rows.getValue("a").durationMs)
    }

    @Test fun `a large offline backlog is drained in batches`() = runTest {
        val dao = FakeEventDao(); val remote = FakeRemote()
        val sync = EventSyncManager(dao, remote)
        (1..120).forEach { dao.upsert(ev("e$it", it.toLong()).toEntity()) }
        assertEquals(120, sync.syncPending(50).uploaded)
        assertEquals(0, dao.rows.values.count { !it.synced })
    }

    @Test fun `events of an unknown future type do not block the queue`() = runTest {
        val dao = FakeEventDao(); val remote = FakeRemote()
        val sync = EventSyncManager(dao, remote)
        dao.upsert(ev("x", 1).toEntity().copy(type = "SOMETHING_NEW"))
        dao.upsert(ev("y", 2).toEntity())
        val r = sync.syncPending()
        assertEquals(1, r.uploaded)
        assertEquals(listOf("y"), remote.uploaded)
        assertTrue(dao.rows.values.all { it.synced })
    }
}
