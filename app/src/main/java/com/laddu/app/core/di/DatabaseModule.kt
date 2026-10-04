package com.laddu.app.core.di

import android.content.Context
import androidx.room.Room
import com.laddu.app.core.database.EventDao
import com.laddu.app.core.database.LadduDatabase
import com.laddu.app.core.firebase.AuthRepository
import com.laddu.app.core.firebase.FirebaseAuthRepository
import com.laddu.app.core.firebase.FirestoreEventRemote
import com.laddu.app.core.sync.EventRemote
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): LadduDatabase =
        Room.databaseBuilder(ctx, LadduDatabase::class.java, LadduDatabase.NAME)
            .addMigrations(*LadduDatabase.MIGRATIONS)
            .build()

    @Provides fun eventDao(db: LadduDatabase): EventDao = db.events()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun auth(impl: FirebaseAuthRepository): AuthRepository
    @Binds abstract fun eventRemote(impl: FirestoreEventRemote): EventRemote
    @Binds abstract fun dogEngine(impl: com.laddu.app.core.ai.TfliteDogDetectionEngine): com.laddu.app.core.ai.DogDetectionEngine
    @Binds abstract fun barkEngine(impl: com.laddu.app.core.audio.TfliteBarkDetectionEngine): com.laddu.app.core.audio.BarkDetectionEngine
}
