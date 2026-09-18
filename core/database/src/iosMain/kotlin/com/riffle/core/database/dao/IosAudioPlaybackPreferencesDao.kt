package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudioPlaybackPreferencesEntity
import com.riffle.core.database.IosInvalidator

internal class IosAudioPlaybackPreferencesDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : AudioPlaybackPreferencesDao {

    override suspend fun upsert(entity: AudioPlaybackPreferencesEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO audio_playback_preferences (sourceId, bookId, speed) VALUES (?, ?, ?)",
            3,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.bookId)
            bindDouble(2, entity.speed?.toDouble())
        }
        invalidator.invalidate()
    }

    override suspend fun get(sourceId: String, bookId: String): AudioPlaybackPreferencesEntity? =
        driver.executeQuery(
            null,
            "SELECT sourceId, bookId, speed FROM audio_playback_preferences WHERE sourceId = ? AND bookId = ? LIMIT 1",
            { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) {
                        AudioPlaybackPreferencesEntity(
                            sourceId = cursor.getString(0)!!,
                            bookId = cursor.getString(1)!!,
                            speed = cursor.getDouble(2)?.toFloat(),
                        )
                    } else {
                        null
                    },
                )
            },
            2,
        ) { bindString(0, sourceId); bindString(1, bookId) }.value

    override suspend fun delete(sourceId: String, bookId: String) {
        driver.execute(
            null,
            "DELETE FROM audio_playback_preferences WHERE sourceId = ? AND bookId = ?",
            2,
        ) { bindString(0, sourceId); bindString(1, bookId) }
        invalidator.invalidate()
    }
}
