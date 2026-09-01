@file:Suppress("TooManyFunctions", "UnusedParameter")

package com.riffle.core.database.dao

import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudioPlaybackPreferencesEntity
import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookChapterCacheEntity
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.database.BookComicFormattingPreferencesDao
import com.riffle.core.database.BookComicFormattingPreferencesEntity
import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.database.CollectionDao
import com.riffle.core.database.CollectionEntity
import com.riffle.core.database.CollectionItemEntity
import com.riffle.core.database.CoverGridScaleDao
import com.riffle.core.database.CrossEpubIndexDao
import com.riffle.core.database.CrossEpubIndexEntity
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.database.DictionaryPackEntity
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFileMetadataOverrideDao
import com.riffle.core.database.LocalFileMetadataOverrideEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFileFolderEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.database.LookupHistoryDao
import com.riffle.core.database.LookupHistoryEntity
import com.riffle.core.database.PublicationMetricsCacheDao
import com.riffle.core.database.PublicationMetricsCacheEntity
import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudCandidateEntity
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudDismissalEntity
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.database.ReadaloudResumePositionDao
import com.riffle.core.database.ReadaloudResumePositionEntity
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.database.RemoteItemFreshnessDao
import com.riffle.core.database.RemoteItemFreshnessEntity
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SeriesEntity
import com.riffle.core.database.SeriesItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

internal object IosNoOpSeriesDao : SeriesDao {
    override fun observeByLibraryId(libraryId: String): Flow<List<SeriesEntity>> = emptyFlow()
    override fun observeItemsBySeriesId(sourceId: String, seriesId: String): Flow<List<LibraryItemEntity>> = emptyFlow()
    override fun observeContinueSeriesItems(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = emptyFlow()
    override suspend fun findSeriesIdForItem(sourceId: String, itemId: String): String? = null
    override suspend fun upsertAll(series: List<SeriesEntity>) = Unit
    override suspend fun upsertAllItems(items: List<SeriesItemEntity>) = Unit
    override suspend fun deleteByLibraryId(libraryId: String) = Unit
    override suspend fun deleteItemsByLibraryId(libraryId: String) = Unit
}

internal object IosNoOpCollectionDao : CollectionDao {
    override fun observeByLibraryId(libraryId: String): Flow<List<CollectionEntity>> = emptyFlow()
    override fun observeItemsByCollectionId(sourceId: String, collectionId: String): Flow<List<LibraryItemEntity>> = emptyFlow()
    override suspend fun upsertAll(collections: List<CollectionEntity>) = Unit
    override suspend fun upsertAllItems(items: List<CollectionItemEntity>) = Unit
    override suspend fun deleteByLibraryId(libraryId: String) = Unit
    override suspend fun deleteItemsByLibraryId(libraryId: String) = Unit
}

internal object IosNoOpReadingPositionDao : ReadingPositionDao {
    override suspend fun upsert(entity: ReadingPositionEntity) = Unit
    override suspend fun getByItemId(sourceId: String, itemId: String): ReadingPositionEntity? = null
    override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) = Unit
    override suspend fun acceptServerIfUnchanged(sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long): Int = 0
    override suspend fun confirmPushedIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long): Int = 0
    override suspend fun confirmInSyncIfUnchanged(sourceId: String, itemId: String, ifLocalUpdatedAt: Long): Int = 0
    override suspend fun dirtyForSource(sourceId: String): List<ReadingPositionEntity> = emptyList()
    override suspend fun sourcesWithDirtyRows(): List<String> = emptyList()
    override suspend fun allForSource(sourceId: String): List<ReadingPositionEntity> = emptyList()
}

internal object IosNoOpBookFormattingPreferencesDao : BookFormattingPreferencesDao {
    override suspend fun upsert(entity: BookFormattingPreferencesEntity) = Unit
    override suspend fun getByItemId(sourceId: String, itemId: String, screenDimensionBucket: String): BookFormattingPreferencesEntity? = null
    override suspend fun deleteByItemId(sourceId: String, itemId: String, screenDimensionBucket: String) = Unit
}

internal object IosNoOpReadaloudLinkDao : ReadaloudLinkDao {
    override suspend fun upsert(entity: ReadaloudLinkEntity) = Unit
    override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLinkEntity? = null
    override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLinkEntity> = emptyList()
    override fun observeAll(): Flow<List<ReadaloudLinkEntity>> = emptyFlow()
    override suspend fun allRows(): List<ReadaloudLinkEntity> = emptyList()
    override fun observeLinkedAbsItemIds(): Flow<List<String>> = emptyFlow()
    override suspend fun countForSource(sourceId: String): Int = 0
    override suspend fun deleteByAbsItem(absSourceId: String, absLibraryItemId: String) = Unit
    override suspend fun deleteByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) = Unit
    override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: String) = Unit
}

internal object IosNoOpReadaloudCandidateDao : ReadaloudCandidateDao {
    override suspend fun upsert(entity: ReadaloudCandidateEntity) = Unit
    override suspend fun upsertAll(entities: List<ReadaloudCandidateEntity>) = Unit
    override suspend fun allRows(): List<ReadaloudCandidateEntity> = emptyList()
    override suspend fun clearAll() = Unit
    override fun observeAll(): Flow<List<ReadaloudCandidateEntity>> = emptyFlow()
    override fun observeForStorytellerSource(storytellerSourceId: String): Flow<List<ReadaloudCandidateEntity>> = emptyFlow()
    override suspend fun deleteByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) = Unit
    override suspend fun deleteCandidate(storytellerSourceId: String, storytellerBookId: String, absSourceId: String, absLibraryItemId: String) = Unit
}

internal object IosNoOpReadaloudDismissalDao : ReadaloudDismissalDao {
    override suspend fun upsert(entity: ReadaloudDismissalEntity) = Unit
    override suspend fun allRows(): List<ReadaloudDismissalEntity> = emptyList()
    override fun observeAll(): Flow<List<ReadaloudDismissalEntity>> = emptyFlow()
    override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudDismissalEntity> = emptyList()
    override suspend fun isBookDismissed(storytellerSourceId: String, storytellerBookId: String): Boolean = false
    override suspend fun clearBookDismissal(storytellerSourceId: String, storytellerBookId: String) = Unit
}

internal object IosNoOpCrossEpubIndexDao : CrossEpubIndexDao {
    override suspend fun upsert(entity: CrossEpubIndexEntity) = Unit
    override suspend fun find(absEpubChecksum: String, storytellerEpubChecksum: String): CrossEpubIndexEntity? = null
    override suspend fun clear() = Unit
}

internal object IosNoOpReadaloudResumePositionDao : ReadaloudResumePositionDao {
    override suspend fun upsert(entity: ReadaloudResumePositionEntity) = Unit
    override suspend fun getByItemId(sourceId: String, itemId: String): ReadaloudResumePositionEntity? = null
    override suspend fun deleteByItemId(sourceId: String, itemId: String) = Unit
}

internal object IosNoOpAudioPlaybackPreferencesDao : AudioPlaybackPreferencesDao {
    override suspend fun upsert(entity: AudioPlaybackPreferencesEntity) = Unit
    override suspend fun get(sourceId: String, bookId: String): AudioPlaybackPreferencesEntity? = null
    override suspend fun delete(sourceId: String, bookId: String) = Unit
}

internal object IosNoOpAudiobookPositionDao : AudiobookPositionDao {
    override suspend fun upsert(entity: AudiobookPositionEntity) = Unit
    override suspend fun getByItemId(sourceId: String, itemId: String): AudiobookPositionEntity? = null
    override suspend fun acceptServerIfUnchanged(sourceId: String, itemId: String, positionSec: Double, serverStamp: Long, ifLocalUpdatedAt: Long): Int = 0
    override suspend fun confirmPushedIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long): Int = 0
    override suspend fun confirmInSyncIfUnchanged(sourceId: String, itemId: String, ifLocalUpdatedAt: Long): Int = 0
    override suspend fun dirtyForSource(sourceId: String): List<AudiobookPositionEntity> = emptyList()
    override suspend fun sourcesWithDirtyRows(): List<String> = emptyList()
    override suspend fun allForSource(sourceId: String): List<AudiobookPositionEntity> = emptyList()
}

internal object IosNoOpAudiobookBookmarkDao : AudiobookBookmarkDao {
    override suspend fun upsert(entity: AudiobookBookmarkEntity) = Unit
    override fun observeForItem(sourceId: String, itemId: String): Flow<List<AudiobookBookmarkEntity>> = emptyFlow()
    override fun observeForSource(sourceId: String): Flow<List<AudiobookBookmarkEntity>> = emptyFlow()
    override suspend fun getById(id: String): AudiobookBookmarkEntity? = null
    override suspend fun allForItem(sourceId: String, itemId: String): List<AudiobookBookmarkEntity> = emptyList()
    override suspend fun dirtyForSource(sourceId: String): List<AudiobookBookmarkEntity> = emptyList()
    override suspend fun sourcesWithDirtyRows(): List<String> = emptyList()
    override fun observeDirtyCountForItem(sourceId: String, itemId: String): Flow<Int> = flowOf(0)
    override suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Int = 0
    override suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Int = 0
    override suspend fun hardDelete(id: String) = Unit
}

internal object IosNoOpAudiobookChapterCacheDao : AudiobookChapterCacheDao {
    override suspend fun get(sourceId: String, itemId: String): AudiobookChapterCacheEntity? = null
    override suspend fun upsert(entity: AudiobookChapterCacheEntity) = Unit
}

internal object IosNoOpLocalFilesFolderDao : LocalFilesFolderDao {
    override suspend fun upsert(entity: LocalFilesFolderEntity) = Unit
    override suspend fun forSource(sourceId: String): List<LocalFilesFolderEntity> = emptyList()
    override fun observeForSource(sourceId: String): Flow<List<LocalFilesFolderEntity>> = emptyFlow()
    override suspend fun getByLibraryId(sourceId: String, libraryId: String): LocalFilesFolderEntity? = null
    override suspend fun delete(sourceId: String, treeUri: String) = Unit
}

internal object IosNoOpLocalFilesFileDao : LocalFilesFileDao {
    override suspend fun upsert(entity: LocalFilesFileEntity) = Unit
    override suspend fun findById(sourceId: String, sourceItemId: String): LocalFilesFileEntity? = null
    override suspend fun forSource(sourceId: String): List<LocalFilesFileEntity> = emptyList()
    override suspend fun getForItems(sourceId: String, sourceItemIds: List<String>): List<LocalFilesFileEntity> = emptyList()
    override suspend fun touchLastSeen(sourceId: String, sourceItemId: String, seenAt: Long) = Unit
    override suspend fun updateDisplayName(sourceId: String, sourceItemId: String, displayName: String) = Unit
    override suspend fun delete(sourceId: String, sourceItemId: String) = Unit
}

internal object IosNoOpLocalFilesFileFolderDao : LocalFilesFileFolderDao {
    override suspend fun upsert(entity: LocalFilesFileFolderEntity) = Unit
    override suspend fun forFile(sourceId: String, sourceItemId: String): List<LocalFilesFileFolderEntity> = emptyList()
    override suspend fun forFolder(sourceId: String, folderTreeUri: String): List<LocalFilesFileFolderEntity> = emptyList()
    override suspend fun itemIdsInFolder(sourceId: String, folderTreeUri: String): List<String> = emptyList()
    override suspend fun stale(sourceId: String, scanStart: Long): List<LocalFilesFileFolderEntity> = emptyList()
    override suspend fun delete(sourceId: String, sourceItemId: String, folderTreeUri: String) = Unit
    override suspend fun deleteFolder(sourceId: String, folderTreeUri: String) = Unit
    override suspend fun deleteFile(sourceId: String, sourceItemId: String) = Unit
    override suspend fun orphanedFiles(sourceId: String): List<LocalFilesFileEntity> = emptyList()
}

internal object IosNoOpLocalFileMetadataOverrideDao : LocalFileMetadataOverrideDao {
    override suspend fun upsert(entity: LocalFileMetadataOverrideEntity) = Unit
    override fun observe(sourceId: String, sourceItemId: String): Flow<LocalFileMetadataOverrideEntity?> = flowOf(null)
    override suspend fun getForItems(sourceId: String, sourceItemIds: List<String>): List<LocalFileMetadataOverrideEntity> = emptyList()
    override suspend fun getForItem(sourceId: String, sourceItemId: String): LocalFileMetadataOverrideEntity? = null
    override suspend fun delete(sourceId: String, sourceItemId: String) = Unit
}

internal object IosNoOpRemoteItemFreshnessDao : RemoteItemFreshnessDao {
    override suspend fun upsert(entity: RemoteItemFreshnessEntity) = Unit
    override suspend fun lastFetchedAt(sourceId: String, sourceItemId: String): Long? = null
    override suspend fun clear(sourceId: String, sourceItemId: String) = Unit
}

internal object IosNoOpPublicationMetricsCacheDao : PublicationMetricsCacheDao {
    override suspend fun get(sourceId: String, itemId: String): PublicationMetricsCacheEntity? = null
    override suspend fun upsert(entity: PublicationMetricsCacheEntity) = Unit
}

internal object IosNoOpBookComicFormattingPreferencesDao : BookComicFormattingPreferencesDao {
    override suspend fun upsert(entity: BookComicFormattingPreferencesEntity) = Unit
    override suspend fun getByItemId(sourceId: String, itemId: String): BookComicFormattingPreferencesEntity? = null
    override suspend fun deleteByItemId(sourceId: String, itemId: String) = Unit
}

internal object IosNoOpDictionaryPackDao : DictionaryPackDao {
    override fun observeForLanguage(languageTag: String): Flow<DictionaryPackEntity?> = flowOf(null)
    override fun observeAll(): Flow<List<DictionaryPackEntity>> = emptyFlow()
    override suspend fun upsert(entity: DictionaryPackEntity) = Unit
    override suspend fun updateState(languageTag: String, state: String) = Unit
    override suspend fun delete(languageTag: String) = Unit
}

internal object IosNoOpLookupHistoryDao : LookupHistoryDao {
    override fun observeRecent(languageTag: String, limit: Int): Flow<List<String>> = emptyFlow()
    override suspend fun insert(entity: LookupHistoryEntity) = Unit
    override suspend fun pruneOldest(languageTag: String) = Unit
}

internal object IosNoOpCoverGridScaleDao : CoverGridScaleDao {
    override suspend fun upsert(entity: com.riffle.core.database.CoverGridScaleEntity) = Unit
    override fun observeScale(sourceId: String, libraryId: String, bucket: String): Flow<Float?> = flowOf(null)
}
