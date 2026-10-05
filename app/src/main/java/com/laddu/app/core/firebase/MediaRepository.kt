package com.laddu.app.core.firebase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Viewer side: pulls event snapshots / clips from Firebase Storage (only exists if the owner enabled cloud upload). */
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val fb: FirebaseProvider,
) {
    suspend fun snapshot(ref: String): Result<Bitmap> = runCatching {
        withContext(Dispatchers.IO) {
            val bytes = fb.storage.reference.child(ref).getBytes(3L * 1024 * 1024).await()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Image could not be decoded")
        }
    }

    /** Downloads to the cache (re-used on the next open). */
    suspend fun clip(ref: String, eventId: String): Result<File> = runCatching {
        withContext(Dispatchers.IO) {
            val f = File(File(ctx.cacheDir, "shared").apply { mkdirs() }, "$eventId.mp4")
            if (!f.exists() || f.length() == 0L) {
                // download beside the target and rename: an interrupted download must never look like a finished clip
                val part = File(f.parentFile, "$eventId.part")
                part.delete()
                try {
                    fb.storage.reference.child(ref).getFile(part).await()
                    if (!part.renameTo(f)) error("Could not save the clip")
                } finally { part.delete() }
            }
            f
        }
    }
}
