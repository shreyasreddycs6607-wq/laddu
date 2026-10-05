package com.laddu.app.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/** Local source of truth for events on the camera phone. [synced] = false until uploaded. */
@Entity(tableName = "events", indices = [Index("synced"), Index("timestamp"), Index("cameraId")])
data class EventEntity(
    @PrimaryKey val eventId: String,
    val cameraId: String,
    val ownerId: String,
    val type: String,
    val timestamp: Long,
    val durationMs: Long,
    val confidence: Float,
    val ongoing: Boolean,
    val metadataJson: String,
    val snapshotRef: String?,
    val clipRef: String?,
    val localSnapshot: String?,
    val localClip: String?,
    val notify: Boolean,
    val synced: Boolean,
    /** Bumped on every local change; lets us avoid marking a newer version as synced. */
    val updatedAt: Long,
)

fun LadduEvent.toEntity(synced: Boolean = false, updatedAt: Long = System.currentTimeMillis()) = EventEntity(
    eventId, cameraId, ownerId, type.name, timestamp, durationMs, confidence, ongoing,
    JSONObject(metadata as Map<*, *>).toString(), snapshotRef, clipRef, localSnapshot, localClip, notify, synced, updatedAt,
)

fun EventEntity.toModel(): LadduEvent? {
    val t = EventType.parse(type) ?: return null
    val meta = runCatching {
        val j = JSONObject(metadataJson)
        j.keys().asSequence().associateWith { j.optString(it) }
    }.getOrDefault(emptyMap())
    return LadduEvent(
        eventId, cameraId, ownerId, t, timestamp, durationMs, confidence, ongoing, meta,
        snapshotRef, clipRef, localSnapshot, localClip, notify,
    )
}

@Dao
interface EventDao {
    @Upsert suspend fun upsert(e: EventEntity)

    @Query("SELECT * FROM events WHERE eventId = :id") suspend fun get(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun unsynced(limit: Int): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE synced = 0") fun pendingCount(): Flow<Int>

    /** Only marks synced if the row was not modified since it was read for upload. */
    @Query("UPDATE events SET synced = 1 WHERE eventId = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long): Int

    @Query("SELECT * FROM events WHERE cameraId = :cameraId ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(cameraId: String, limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE cameraId = :cameraId AND timestamp >= :since ORDER BY timestamp DESC")
    suspend fun since(cameraId: String, since: Long): List<EventEntity>

    @Query("DELETE FROM events WHERE synced = 1 AND timestamp < :before") suspend fun deleteSyncedBefore(before: Long): Int

    @Query("UPDATE events SET ongoing = 0, synced = 0, updatedAt = :now WHERE ongoing = 1") suspend fun closeOngoing(now: Long): Int

    @Query("DELETE FROM events") suspend fun clear()
}

@Database(entities = [EventEntity::class], version = 1, exportSchema = true)
abstract class LadduDatabase : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        const val NAME = "laddu.db"
        // Add Migration objects here when the schema changes (never fall back to destructive).
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()
    }
}
