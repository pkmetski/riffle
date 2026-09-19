package com.riffle.shared.library

import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.CatalogImportResult
import com.riffle.core.domain.CrossEpubIndexBuildTrigger
import com.riffle.core.domain.ReadaloudSidecarPrefetcher
import com.riffle.core.models.ReadaloudLink
import com.riffle.feature.library.BookImportManager
import com.riffle.feature.library.BookImportState
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

// IosNoOpLocalFileMetadataOverrideSaver removed (issue #1065): iOS now binds
// SaveLocalFileMetadataOverrideUseCase (core:data commonMain) via Koin.kt.

// IosNoOpPdfRepository / IosNoOpPdfPageCountExtractor / IosNoOpCoverImageCopier removed (issue
// #1065): iOS now binds IosPdfRepositoryImpl (streams the ABS file endpoint into pdf-downloads),
// IosPdfPageCountExtractor (PDFKit PDFDocument.pageCount, cached through
// PublicationMetricsRepository) and IosCoverImageCopier (security-scoped read into local-covers).
