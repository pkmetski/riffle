package com.riffle.shared.library

import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.CatalogImportResult
import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.CrossEpubIndexBuildTrigger
import com.riffle.core.domain.EbookCfiTranslator
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudSidecarPrefetcher
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.TocEntry
import com.riffle.feature.library.BookImportManager
import com.riffle.feature.library.BookImportState
import com.riffle.feature.library.CoverImageCopier
import com.riffle.feature.library.EpubDetails
import com.riffle.feature.library.EpubTocExtractor
import com.riffle.feature.library.LocalFileMetadataOverrideSaver
import com.riffle.feature.library.PdfPageCountExtractor
import com.riffle.feature.library.ReadaloudOfflineDownloader
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal object IosNoOpEbookCfiTranslatorFactory : EbookCfiTranslatorFactory {
    override fun forItem(sourceId: String, itemId: String): EbookCfiTranslator? = null
}

internal class IosNoOpPdfRepository : PdfRepository {
    override suspend fun downloadPdf(item: LibraryItem, onProgress: (Long, Long) -> Unit): PdfDownloadResult =
        PdfDownloadResult.AlreadyDownloaded
    override suspend fun removeDownload(sourceId: String, itemId: String) {}
    override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
    override fun isCached(sourceId: String, itemId: String): Boolean = false
    override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) {}
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

internal object IosNoOpReadaloudSidecarPrefetcher : ReadaloudSidecarPrefetcher {
    override fun prepare(storytellerSourceId: String, storytellerBookId: String) {}
}

internal class IosNoOpAudiobookChapterCacheRepository : AudiobookChapterCacheRepository {
    override suspend fun getCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>? = null
    override suspend fun getStaleCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>? = null
    override suspend fun fetchAndCacheChapters(sourceId: String, itemId: String): List<AudiobookChapter> = emptyList()
}

internal object IosNoOpReadaloudOfflineDownloader : ReadaloudOfflineDownloader {
    override suspend fun download(
        storytellerSourceId: String,
        storytellerBookId: String,
        onProgress: (Float) -> Unit,
    ): Boolean? = null
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
