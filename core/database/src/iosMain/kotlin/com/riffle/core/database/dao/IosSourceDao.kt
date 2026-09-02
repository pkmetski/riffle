package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

internal class IosSourceDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : SourceDao {
    override fun observeAll(): Flow<List<SourceEntity>> =
        invalidator.version.flatMapLatest { flow { emit(querySources()) } }

    override suspend fun getActive(): SourceEntity? =
        driver.executeQuery(
            null,
            "SELECT id, url, isActive, insecureConnectionAllowed, username, serverType, absUserId, type FROM sources WHERE isActive = 1 LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.toSourceEntity() else null) },
            0,
        ).value

    override suspend fun upsert(source: SourceEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO sources (id, url, isActive, insecureConnectionAllowed, username, serverType, absUserId, type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            8,
        ) {
            bindString(0, source.id)
            bindString(1, source.url)
            bindLong(2, if (source.isActive) 1L else 0L)
            bindLong(3, if (source.insecureConnectionAllowed) 1L else 0L)
            bindString(4, source.username)
            bindString(5, source.serverType)
            bindString(6, source.absUserId)
            bindString(7, source.type)
        }
        invalidator.invalidate()
    }

    override suspend fun clearActiveFlag() {
        driver.execute(null, "UPDATE sources SET isActive = 0", 0)
        invalidator.invalidate()
    }

    override suspend fun setActive(id: String) {
        driver.execute(null, "UPDATE sources SET isActive = 1 WHERE id = ?", 1) {
            bindString(0, id)
        }
        invalidator.invalidate()
    }

    override suspend fun setActiveAtomic(id: String) {
        withTransaction {
            driver.execute(null, "UPDATE sources SET isActive = 0", 0)
            driver.execute(null, "UPDATE sources SET isActive = 1 WHERE id = ?", 1) { bindString(0, id) }
        }
        invalidator.invalidate()
    }

    override suspend fun upsertAsFirstIfNoActive(source: SourceEntity): SourceEntity {
        return withTransactionResult {
            val hasActive = getActive() != null
            val toInsert = source.copy(isActive = !hasActive)
            upsert(toInsert)
            toInsert
        }
    }

    override suspend fun getById(id: String): SourceEntity? =
        driver.executeQuery(
            null,
            "SELECT id, url, isActive, insecureConnectionAllowed, username, serverType, absUserId, type FROM sources WHERE id = ? LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.toSourceEntity() else null) },
            1,
        ) { bindString(0, id) }.value

    override suspend fun getByType(type: String): SourceEntity? =
        driver.executeQuery(
            null,
            "SELECT id, url, isActive, insecureConnectionAllowed, username, serverType, absUserId, type FROM sources WHERE type = ? LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.toSourceEntity() else null) },
            1,
        ) { bindString(0, type) }.value

    override suspend fun deleteById(id: String) {
        driver.execute(null, "DELETE FROM sources WHERE id = ?", 1) { bindString(0, id) }
        invalidator.invalidate()
    }

    override suspend fun setAbsUserId(id: String, absUserId: String) {
        driver.execute(null, "UPDATE sources SET absUserId = ? WHERE id = ?", 2) {
            bindString(0, absUserId)
            bindString(1, id)
        }
        invalidator.invalidate()
    }

    // deleteSourceGraph calls these; tables that don't exist on iOS are no-ops.

    override suspend fun deleteReadaloudLinksForSource(id: String) = Unit
    override suspend fun deleteReadaloudCandidatesForSource(id: String) = Unit
    override suspend fun deleteReadaloudDismissalsForSource(id: String) = Unit

    override suspend fun deleteSeriesForSource(id: String) {
        driver.execute(null, "DELETE FROM series WHERE sourceId = ?", 1) { bindString(0, id) }
    }

    override suspend fun deleteSeriesItemsForSource(id: String) {
        driver.execute(null, "DELETE FROM series_items WHERE sourceId = ?", 1) { bindString(0, id) }
    }

    override suspend fun deleteCollectionsForSource(id: String) {
        driver.execute(null, "DELETE FROM collections WHERE sourceId = ?", 1) { bindString(0, id) }
    }

    override suspend fun deleteCollectionItemsForSource(id: String) {
        driver.execute(null, "DELETE FROM collection_items WHERE sourceId = ?", 1) { bindString(0, id) }
    }

    override suspend fun deletePlaylistItemsForSource(id: String) {
        driver.execute(null, "DELETE FROM playlist_items WHERE sourceId = ?", 1) { bindString(0, id) }
    }

    override suspend fun deletePlaylistsForSource(id: String) {
        driver.execute(null, "DELETE FROM playlists WHERE sourceId = ?", 1) { bindString(0, id) }
    }

    override suspend fun deleteReadingPositionsForSource(id: String) = Unit
    override suspend fun deleteBookFormattingPreferencesForSource(id: String) = Unit

    override suspend fun deleteAnnotationsForSource(id: String) {
        driver.execute(null, "DELETE FROM annotations WHERE sourceId = ?", 1) { bindString(0, id) }
    }

    override suspend fun deleteReadaloudResumePositionsForSource(id: String) = Unit
    override suspend fun deleteAudioPlaybackPreferencesForSource(id: String) = Unit
    override suspend fun deleteAudiobookPositionsForSource(id: String) = Unit
    override suspend fun deleteAudiobookBookmarksForSource(id: String) = Unit

    override suspend fun deleteTocCacheForSource(id: String) {
        driver.execute(null, "DELETE FROM toc_cache WHERE sourceId = ?", 1) { bindString(0, id) }
    }

    override suspend fun deleteAudiobookChapterCacheForSource(id: String) = Unit
    override suspend fun deleteLocalFilesFileFoldersForSource(id: String) = Unit
    override suspend fun deleteLocalFilesFilesForSource(id: String) = Unit
    override suspend fun deleteLocalFilesFoldersForSource(id: String) = Unit
    override suspend fun deleteLocalFileMetadataOverridesForSource(id: String) = Unit
    override suspend fun deleteRemoteItemFreshnessForSource(id: String) = Unit
    override suspend fun deletePublicationMetricsCacheForSource(id: String) = Unit

    override suspend fun deleteLibraryItemsForSource(id: String) {
        driver.execute(null, "DELETE FROM library_items WHERE sourceId = ?", 1) { bindString(0, id) }
    }

    override suspend fun deleteLibrariesForSource(id: String) {
        driver.execute(null, "DELETE FROM libraries WHERE sourceId = ?", 1) { bindString(0, id) }
    }

    private fun querySources(): List<SourceEntity> =
        driver.executeQuery(
            null,
            "SELECT id, url, isActive, insecureConnectionAllowed, username, serverType, absUserId, type FROM sources ORDER BY serverType ASC, username ASC, url ASC",
            { cursor ->
                val result = mutableListOf<SourceEntity>()
                while (cursor.next().value) result.add(cursor.toSourceEntity())
                QueryResult.Value(result)
            },
            0,
        ).value

    private fun app.cash.sqldelight.db.SqlCursor.toSourceEntity() = SourceEntity(
        id = getString(0)!!,
        url = getString(1)!!,
        isActive = getLong(2)!! != 0L,
        insecureConnectionAllowed = getLong(3)!! != 0L,
        username = getString(4)!!,
        serverType = getString(5) ?: "AUDIOBOOKSHELF",
        absUserId = getString(6),
        type = getString(7) ?: "ABS",
    )

    private inline fun withTransaction(block: () -> Unit) {
        driver.execute(null, "BEGIN IMMEDIATE", 0)
        try {
            block()
            driver.execute(null, "COMMIT", 0)
        } catch (e: Throwable) {
            driver.execute(null, "ROLLBACK", 0)
            throw e
        }
    }

    private inline fun <T> withTransactionResult(block: () -> T): T {
        driver.execute(null, "BEGIN IMMEDIATE", 0)
        try {
            val result = block()
            driver.execute(null, "COMMIT", 0)
            return result
        } catch (e: Throwable) {
            driver.execute(null, "ROLLBACK", 0)
            throw e
        }
    }
}
