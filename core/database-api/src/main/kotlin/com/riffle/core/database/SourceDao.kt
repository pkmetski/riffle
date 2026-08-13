package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {

    // Order is stable across active-source changes so the switcher list doesn't reshuffle on
    // every switch — the check-mark alone identifies the active row.
    @Query("SELECT * FROM sources ORDER BY serverType ASC, username ASC, url ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    @Query("UPDATE sources SET isActive = 0")
    suspend fun clearActiveFlag()

    @Query("UPDATE sources SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Transaction
    suspend fun setActiveAtomic(id: String) {
        clearActiveFlag()
        setActive(id)
    }

    @Transaction
    suspend fun upsertAsFirstIfNoActive(source: SourceEntity): SourceEntity {
        val hasActive = getActive() != null
        val toInsert = source.copy(isActive = !hasActive)
        upsert(toInsert)
        return toInsert
    }

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SourceEntity?

    /**
     * First source row whose [SourceEntity.type] matches [type] (there is at most one LocalFiles
     * source per device; this returns it or `null` when none has been installed yet).
     */
    @Query("SELECT * FROM sources WHERE type = :type LIMIT 1")
    suspend fun getByType(type: String): SourceEntity?

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM readaloud_links WHERE storytellerSourceId = :id OR absSourceId = :id")
    suspend fun deleteReadaloudLinksForSource(id: String)

    @Query("DELETE FROM readaloud_candidates WHERE storytellerSourceId = :id OR absSourceId = :id")
    suspend fun deleteReadaloudCandidatesForSource(id: String)

    @Query("DELETE FROM readaloud_dismissals WHERE storytellerSourceId = :id OR absSourceId = :id")
    suspend fun deleteReadaloudDismissalsForSource(id: String)

    @Query("DELETE FROM series WHERE id IN (SELECT seriesId FROM series_items WHERE sourceId = :id)")
    suspend fun deleteSeriesForSource(id: String)

    @Query("DELETE FROM series_items WHERE sourceId = :id")
    suspend fun deleteSeriesItemsForSource(id: String)

    @Query("DELETE FROM collections WHERE id IN (SELECT collectionId FROM collection_items WHERE sourceId = :id)")
    suspend fun deleteCollectionsForSource(id: String)

    @Query("DELETE FROM collection_items WHERE sourceId = :id")
    suspend fun deleteCollectionItemsForSource(id: String)

    @Query("DELETE FROM playlist_items WHERE sourceId = :id")
    suspend fun deletePlaylistItemsForSource(id: String)

    @Query("DELETE FROM playlists WHERE sourceId = :id")
    suspend fun deletePlaylistsForSource(id: String)

    @Query("DELETE FROM reading_positions WHERE sourceId = :id")
    suspend fun deleteReadingPositionsForSource(id: String)

    @Query("DELETE FROM book_formatting_preferences WHERE sourceId = :id")
    suspend fun deleteBookFormattingPreferencesForSource(id: String)

    @Query("DELETE FROM annotations WHERE sourceId = :id")
    suspend fun deleteAnnotationsForSource(id: String)

    @Query("DELETE FROM readaloud_resume_positions WHERE sourceId = :id")
    suspend fun deleteReadaloudResumePositionsForSource(id: String)

    @Query("DELETE FROM audio_playback_preferences WHERE sourceId = :id")
    suspend fun deleteAudioPlaybackPreferencesForSource(id: String)

    @Query("DELETE FROM audiobook_positions WHERE sourceId = :id")
    suspend fun deleteAudiobookPositionsForSource(id: String)

    @Query("DELETE FROM audiobook_bookmarks WHERE sourceId = :id")
    suspend fun deleteAudiobookBookmarksForSource(id: String)

    @Query("DELETE FROM toc_cache WHERE sourceId = :id")
    suspend fun deleteTocCacheForSource(id: String)

    @Query("DELETE FROM audiobook_chapter_cache WHERE sourceId = :id")
    suspend fun deleteAudiobookChapterCacheForSource(id: String)

    @Query("DELETE FROM local_files_file_folders WHERE sourceId = :id")
    suspend fun deleteLocalFilesFileFoldersForSource(id: String)

    @Query("DELETE FROM local_files_files WHERE sourceId = :id")
    suspend fun deleteLocalFilesFilesForSource(id: String)

    @Query("DELETE FROM local_files_folders WHERE sourceId = :id")
    suspend fun deleteLocalFilesFoldersForSource(id: String)

    @Query("DELETE FROM local_file_metadata_overrides WHERE sourceId = :id")
    suspend fun deleteLocalFileMetadataOverridesForSource(id: String)

    @Query("DELETE FROM remote_item_freshness WHERE sourceId = :id")
    suspend fun deleteRemoteItemFreshnessForSource(id: String)

    @Query("DELETE FROM publication_metrics_cache WHERE sourceId = :id")
    suspend fun deletePublicationMetricsCacheForSource(id: String)

    @Query("DELETE FROM library_items WHERE sourceId = :id")
    suspend fun deleteLibraryItemsForSource(id: String)

    @Query("DELETE FROM libraries WHERE sourceId = :id")
    suspend fun deleteLibrariesForSource(id: String)

    @Transaction
    suspend fun deleteSourceGraph(id: String) {
        deleteReadaloudLinksForSource(id)
        deleteReadaloudCandidatesForSource(id)
        deleteReadaloudDismissalsForSource(id)
        deleteSeriesForSource(id)
        deleteSeriesItemsForSource(id)
        deleteCollectionsForSource(id)
        deleteCollectionItemsForSource(id)
        deletePlaylistItemsForSource(id)
        deletePlaylistsForSource(id)
        deleteReadingPositionsForSource(id)
        deleteBookFormattingPreferencesForSource(id)
        deleteAnnotationsForSource(id)
        deleteReadaloudResumePositionsForSource(id)
        deleteAudioPlaybackPreferencesForSource(id)
        deleteAudiobookPositionsForSource(id)
        deleteAudiobookBookmarksForSource(id)
        deleteTocCacheForSource(id)
        deleteAudiobookChapterCacheForSource(id)
        deleteLocalFilesFileFoldersForSource(id)
        deleteLocalFilesFilesForSource(id)
        deleteLocalFilesFoldersForSource(id)
        deleteLocalFileMetadataOverridesForSource(id)
        deleteRemoteItemFreshnessForSource(id)
        deletePublicationMetricsCacheForSource(id)
        deleteLibraryItemsForSource(id)
        deleteLibrariesForSource(id)
        deleteById(id)
    }

    @Query("UPDATE sources SET absUserId = :absUserId WHERE id = :id")
    suspend fun setAbsUserId(id: String, absUserId: String)
}
