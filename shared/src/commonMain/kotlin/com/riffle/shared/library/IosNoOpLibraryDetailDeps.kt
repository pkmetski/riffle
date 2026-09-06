package com.riffle.shared.library

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.CatalogImportResult
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.PlaylistsRepository
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzLocalSource
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.CrossEpubIndexBuildTrigger
import com.riffle.core.domain.EbookCfiTranslator
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudSidecarPrefetcher
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.usecase.MarkReadAcrossDimensions
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.models.AudiobookBookmark
import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.models.AudiobookIdentityResult
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ProgressSyncCycleResult
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.Source
import com.riffle.core.models.SyncSessionResult
import com.riffle.core.models.TocEntry
import com.riffle.feature.library.BookImportManager
import com.riffle.feature.library.BookImportState
import com.riffle.feature.library.CoverImageCopier
import com.riffle.feature.library.DownloadManager
import com.riffle.feature.library.DownloadState
import com.riffle.feature.library.EpubDetails
import com.riffle.feature.library.EpubTocExtractor
import com.riffle.feature.library.LocalFileMetadataOverrideSaver
import com.riffle.feature.library.PdfPageCountExtractor
import com.riffle.feature.library.ReadaloudOfflineDownloader
import com.riffle.feature.library.WebSourceLibraryItemUpserter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// -------- Domain no-ops --------

internal class IosNoOpEpubRepository : EpubRepository {
    override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit): EpubDownloadResult =
        EpubDownloadResult.AlreadyDownloaded
    override suspend fun removeDownload(sourceId: String, itemId: String) {}
    override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
    override fun isCached(sourceId: String, itemId: String): Boolean = false
    override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
}

internal object IosNoOpEbookCfiTranslatorFactory : EbookCfiTranslatorFactory {
    override fun forItem(sourceId: String, itemId: String): EbookCfiTranslator? = null
}

internal class IosNoOpAudiobookPositionStore : AudiobookPositionStore {
    override suspend fun save(sourceId: String, itemId: String, payload: Double) {}
    override suspend fun load(sourceId: String, itemId: String): Double? = null
    override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
    override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
    override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
    override suspend fun acceptServer(sourceId: String, itemId: String, payload: Double, serverStamp: Long) {}
    override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {}
}

internal class IosNoOpPdfRepository : PdfRepository {
    override suspend fun downloadPdf(item: LibraryItem, onProgress: (Long, Long) -> Unit): PdfDownloadResult =
        PdfDownloadResult.AlreadyDownloaded
    override suspend fun removeDownload(sourceId: String, itemId: String) {}
    override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
    override fun isCached(sourceId: String, itemId: String): Boolean = false
    override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
}

internal class IosNoOpCbzRepository : CbzRepository {
    override suspend fun openCbz(item: LibraryItem): CbzOpenResult = CbzOpenResult.Offline
    override suspend fun downloadCbz(item: LibraryItem, onProgress: (Long, Long) -> Unit): CbzDownloadResult =
        CbzDownloadResult.AlreadyDownloaded
    override suspend fun removeDownload(sourceId: String, itemId: String) {}
    override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
    override fun isCached(sourceId: String, itemId: String): Boolean = false
    override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
    override suspend fun supportsStreaming(sourceId: String): Boolean = false
    override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray =
        ByteArray(0)
    override suspend fun awaitCachedSource(item: LibraryItem): CbzLocalSource? = null
}

internal class IosNoOpReadaloudAudioRepository : ReadaloudAudioRepository {
    override fun isAudioAvailable(sourceId: String, itemId: String): Boolean = false
    override suspend fun probeSizeBytes(sourceId: String, itemId: String): Long? = null
    override suspend fun downloadAudio(sourceId: String, bookId: String, onProgress: (Long, Long) -> Unit): AudioDownloadResult =
        AudioDownloadResult.NoBundle
    override suspend fun removeAudio(sourceId: String, itemId: String): Long = 0L
}

internal class IosNoOpAudiobookDownloadRepository : AudiobookDownloadRepository {
    override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
    override suspend fun download(sourceId: String, itemId: String, onProgress: (Long, Long) -> Unit): AudiobookDownloadResult =
        AudiobookDownloadResult.NetworkError(UnsupportedOperationException("iOS no-op"))
    override suspend fun remove(sourceId: String, itemId: String): Long = 0L
}

internal class IosNoOpAudiobookCacheRepository : AudiobookCacheRepository {
    override fun isCached(sourceId: String, itemId: String): Boolean = false
    override suspend fun remove(sourceId: String, itemId: String): Long = 0L
}

internal class IosNoOpLocalAvailabilityEvents : LocalAvailabilityEvents {
    override val changes: MutableSharedFlow<StoredItemRef> = MutableSharedFlow()
    override fun notifyChanged(sourceId: String, itemId: String) {}
}

internal object IosNoOpCrossEpubIndexBuildTrigger : CrossEpubIndexBuildTrigger {
    override fun enqueueBuild(link: ReadaloudLink) {}
}

internal class IosNoOpReadingSpeedStore : ReadingSpeedStore {
    override val speedSecPerPosition: Flow<Double> = flowOf(0.0)
    override suspend fun updateSpeed(newSecPerPosition: Double) {}
}

internal object IosNoOpCatalogRegistry : CatalogRegistry {
    override suspend fun forActive(): Catalog? = null
    override suspend fun forSource(source: Source): Catalog? = null
    override suspend fun forSourceId(sourceId: String): Catalog? = null
}

internal object IosNoOpReadaloudSidecarPrefetcher : ReadaloudSidecarPrefetcher {
    override fun prepare(storytellerSourceId: String, storytellerBookId: String) {}
}

// -------- Use case no-ops --------

private object NoOpLibraryMutator : LibraryMutator {
    override suspend fun markItemOpened(itemId: String) {}
    override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {}
    override suspend fun deleteItem(sourceId: String, itemId: String) {}
}

private object NoOpReadingSessionRepository : ReadingSessionRepository {
    override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult =
        SyncSessionResult.Success
    override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult =
        ProgressSyncCycleResult.InSync
    override suspend fun markFinished(itemId: String, finished: Boolean) {}
    override suspend fun touchOpenTimestamp(itemId: String) {}
}

private object NoOpReadaloudLinkRepository : ReadaloudLinkRepository {
    override fun observeAll(): Flow<List<ReadaloudLink>> = flowOf(emptyList())
    override fun observeLinkedAbsItemIds(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? = null
    override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> = emptyList()
    override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) {}
    override suspend fun countForSource(sourceId: String): Int = 0
    override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: AudiobookIdentityResult) {}
}

private object NoOpSourceRepository : SourceRepository {
    override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
    override suspend fun getActive(): Source? = null
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(UnsupportedOperationException("iOS no-op"))
    override suspend fun setActive(sourceId: String) {}
    override suspend fun remove(sourceId: String) {}
    override suspend fun getSourceVersion(sourceId: String): String? = null
    override suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace = SyncNamespace.LocalOnly("iOS no-op")
}

internal class IosNoOpRecordItemOpened : RecordItemOpened(NoOpLibraryMutator, NoOpReadingSessionRepository) {
    override suspend fun invoke(itemId: String) {}
}

internal class IosNoOpUpdateReadingProgress : UpdateReadingProgress(NoOpLibraryMutator) {
    override suspend fun invoke(itemId: String, progress: Float) {}
}

internal class IosNoOpMarkReadAcrossDimensions : MarkReadAcrossDimensions(
    NoOpLibraryMutator,
    NoOpReadingSessionRepository,
    NoOpReadaloudLinkRepository,
    NoOpSourceRepository,
) {
    override suspend fun invoke(itemId: String, finished: Boolean) {}
}

// -------- AudiobookChapterCacheRepository no-op --------

internal class IosNoOpAudiobookChapterCacheRepository : AudiobookChapterCacheRepository {
    override suspend fun getCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>? = null
    override suspend fun getStaleCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>? = null
    override suspend fun fetchAndCacheChapters(sourceId: String, itemId: String): List<AudiobookChapter> = emptyList()
}

// -------- Feature library no-ops --------

internal object IosNoOpReadaloudOfflineDownloader : ReadaloudOfflineDownloader {
    override suspend fun download(
        storytellerSourceId: String,
        storytellerBookId: String,
        onProgress: (Float) -> Unit,
    ): Boolean? = null
}

internal class IosNoOpDownloadManager : DownloadManager {
    override val states: StateFlow<Map<String, DownloadState>> = MutableStateFlow(emptyMap())
    override fun start(key: String, work: suspend (onProgress: (Long, Long) -> Unit) -> DownloadState) {}
    override fun startWithoutProgress(key: String, stateWhileRunning: DownloadState, work: suspend () -> DownloadState) {}
    override fun cancel(key: String) {}
    override fun clear(key: String) {}
}

internal class IosNoOpBookImportManager : BookImportManager {
    override val states: StateFlow<Map<String, BookImportState>> = MutableStateFlow(emptyMap())
    override fun start(
        key: String,
        work: suspend (
            onProgress: (CatalogImportProgress) -> Unit,
            claimItem: (String) -> Boolean,
        ) -> CatalogImportResult,
    ) {}
}

internal class IosNoOpEpubTocExtractor : EpubTocExtractor {
    override suspend fun extract(item: LibraryItem): List<TocEntry> = emptyList()
    override suspend fun extractDetails(item: LibraryItem): EpubDetails = EpubDetails(emptyList(), null)
}

internal object IosNoOpPdfPageCountExtractor : PdfPageCountExtractor {
    override suspend fun extract(item: LibraryItem): Int? = null
}

internal object IosNoOpLocalFileMetadataOverrideSaver : LocalFileMetadataOverrideSaver {
    override suspend fun invoke(
        sourceId: String,
        sourceItemId: String,
        title: String?,
        author: String?,
        seriesName: String?,
        seriesIndex: Double?,
        coverUrl: String?,
    ) {}
}

internal object IosNoOpCoverImageCopier : CoverImageCopier {
    override suspend fun invoke(sourceId: String, sourceItemId: String, contentUriString: String): String? = null
}

internal object IosNoOpWebSourceLibraryItemUpserter : WebSourceLibraryItemUpserter {
    override suspend fun upsert(sourceId: String, item: CatalogItem) {}
}

// -------- PlaylistsRepository and LibraryRefresher no-ops --------

internal class IosNoOpPlaylistsRepository : PlaylistsRepository {
    override fun observePlaylists(rootId: String): Flow<List<CatalogPlaylist>> = flowOf(emptyList())
    override suspend fun refresh(rootId: String): Boolean = false
    override suspend fun getPlaylist(rootId: String, playlistId: String): CatalogPlaylist? = null
    override suspend fun createPlaylist(rootId: String, name: String, initialItemId: String?): CatalogPlaylist =
        throw UnsupportedOperationException("iOS no-op")
    override suspend fun addItemToPlaylist(rootId: String, playlistId: String, itemId: String): Boolean = false
    override suspend fun removeItemFromPlaylist(rootId: String, playlistId: String, itemId: String): Boolean = false
}

internal class IosNoOpLibraryRefresher : LibraryRefresher {
    override suspend fun refreshLibraries(): LibraryRefreshResult = LibraryRefreshResult.NoActiveServer
    override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.NoActiveServer
    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.NoActiveServer
    override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.NoActiveServer
    override suspend fun refreshItemProgress(sourceId: String, itemId: String): LibraryRefreshResult = LibraryRefreshResult.NoActiveServer
}
