package com.riffle.shared.library

import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.CatalogImportResult
import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.CrossEpubIndexBuildTrigger
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudSidecarPrefetcher
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ReadaloudLink
import com.riffle.feature.library.BookImportManager
import com.riffle.feature.library.BookImportState
import com.riffle.feature.library.CoverImageCopier
import com.riffle.feature.library.PdfPageCountExtractor
import com.riffle.feature.library.ReadaloudOfflineDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// IosNoOpEbookCfiTranslatorFactory removed (issue #1065): iOS now binds
// IosEbookCfiTranslatorFactory (shared), backed by a ksoup-based DOM-walking CFI translator.
//
// IosNoOpAudiobookDownloadRepository / IosNoOpAudiobookCacheRepository /
// IosNoOpAudiobookChapterCacheRepository / IosNoOpLocalAvailabilityEvents removed (issue
// #1065): iOS now binds IosAudiobookDownloadRepositoryImpl + IosAudiobookCacheRepositoryImpl
// (core:data, NSFileManager + the shared AudiobookDownloadManifest), and the commonMain
// AudiobookChapterCacheRepositoryImpl / LocalAvailabilityEventsImpl.

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

internal object IosNoOpCrossEpubIndexBuildTrigger : CrossEpubIndexBuildTrigger {
    override fun enqueueBuild(link: ReadaloudLink) {}
}

internal object IosNoOpReadaloudSidecarPrefetcher : ReadaloudSidecarPrefetcher {
    override fun prepare(storytellerSourceId: String, storytellerBookId: String) {}
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

// IosNoOpEpubTocExtractor removed (issue #1065): iOS now binds IosEpubTocExtractor (shared),
// backed by a headless Readium Swift publication inspector.

internal object IosNoOpPdfPageCountExtractor : PdfPageCountExtractor {
    override suspend fun extract(item: LibraryItem): Int? = null
}

// IosNoOpLocalFileMetadataOverrideSaver removed (issue #1065): iOS now binds
// SaveLocalFileMetadataOverrideUseCase (core:data commonMain) via Koin.kt.

internal object IosNoOpCoverImageCopier : CoverImageCopier {
    override suspend fun invoke(sourceId: String, sourceItemId: String, contentUriString: String): String? = null
}
