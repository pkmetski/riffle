package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AudioPlaybackPreferencesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AudioPlaybackPreferencesEntity)

    @Query("SELECT * FROM audio_playback_preferences WHERE sourceId = :sourceId AND bookId = :bookId LIMIT 1")
    suspend fun get(sourceId: String, bookId: String): AudioPlaybackPreferencesEntity?

    @Query("DELETE FROM audio_playback_preferences WHERE sourceId = :sourceId AND bookId = :bookId")
    suspend fun delete(sourceId: String, bookId: String)
}
