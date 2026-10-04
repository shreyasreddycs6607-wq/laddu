package com.laddu.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.laddu.app.core.database.LadduDatabase
import com.laddu.app.core.database.toEntity
import com.laddu.app.core.database.toModel
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Repository-level test of the offline-first store (real Room, in-memory). */
@RunWith(AndroidJUnit4::class)
class RoomEventDaoTest {
    private lateinit var db: LadduDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LadduDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    private fun ev(id: String, ts: Long, meta: Map<String, String> = emptyMap()) =
        LadduEvent(id, "cam", "owner", EventType.BARK, ts, 1000, 0.7f, false, meta, localClip = "/x/$id.mp4")

    @Test fun events_round_trip_with_metadata_and_local_media() = runBlocking {
        db.events().upsert(ev("a", 1, mapOf("count" to "3")).toEntity())
        val back = db.events().get("a")!!.toModel()!!
        assertEquals("3", back.metadata["count"]); assertEquals("/x/a.mp4", back.localClip); assertEquals(EventType.BARK, back.type)
    }

    @Test fun unsynced_queue_is_chronological_and_mark_synced_is_version_guarded() = runBlocking {
        val dao = db.events()
        dao.upsert(ev("b", 2).toEntity(updatedAt = 10)); dao.upsert(ev("a", 1).toEntity(updatedAt = 10))
        assertEquals(listOf("a", "b"), dao.unsynced(10).map { it.eventId })
        assertEquals(2, dao.pendingCount().first())
        assertEquals(0, dao.markSynced("a", updatedAt = 999)) // stale version: refused
        assertEquals(1, dao.markSynced("a", updatedAt = 10))
        assertEquals(listOf("b"), dao.unsynced(10).map { it.eventId })
    }

    @Test fun upsert_updates_the_same_logical_event() = runBlocking {
        val dao = db.events()
        dao.upsert(ev("a", 1).copy(ongoing = true).toEntity())
        dao.upsert(ev("a", 1).copy(ongoing = false, durationMs = 102_000).toEntity())
        assertEquals(1, dao.since("cam", 0).size)
        assertEquals(102_000L, dao.get("a")!!.durationMs)
    }

    @Test fun cleanup_only_removes_old_synced_events() = runBlocking {
        val dao = db.events()
        dao.upsert(ev("old-synced", 1).toEntity(synced = true)); dao.upsert(ev("old-pending", 1).toEntity(synced = false))
        dao.upsert(ev("new-synced", 100).toEntity(synced = true))
        assertEquals(1, dao.deleteSyncedBefore(50))
        assertNull(dao.get("old-synced")); assertTrue(dao.get("old-pending") != null); assertTrue(dao.get("new-synced") != null)
    }
}
