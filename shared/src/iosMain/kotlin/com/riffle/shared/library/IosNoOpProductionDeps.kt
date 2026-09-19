package com.riffle.shared.library

import com.riffle.core.domain.CrossEpubIndexBuildTrigger
import com.riffle.core.models.ReadaloudLink
import com.riffle.feature.library.ReadaloudOfflineDownloader

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

internal object IosNoOpReadaloudOfflineDownloader : ReadaloudOfflineDownloader {
    override suspend fun download(
        storytellerSourceId: String,
        storytellerBookId: String,
        onProgress: (Float) -> Unit,
    ): Boolean? = null
}

// IosNoOpEpubTocExtractor removed (issue #1065): iOS now binds IosEpubTocExtractor (shared),
// backed by a headless Readium Swift publication inspector.

// IosNoOpLocalFileMetadataOverrideSaver removed (issue #1065): iOS now binds
// SaveLocalFileMetadataOverrideUseCase (core:data commonMain) via Koin.kt.

// IosNoOpPdfRepository / IosNoOpPdfPageCountExtractor / IosNoOpCoverImageCopier removed (issue
// #1065): iOS now binds IosPdfRepositoryImpl (streams the ABS file endpoint into pdf-downloads),
// IosPdfPageCountExtractor (PDFKit PDFDocument.pageCount, cached through
// PublicationMetricsRepository) and IosCoverImageCopier (security-scoped read into local-covers).

// IosNoOpBookImportManager removed (issue #1065): iOS now binds the shared
// BookImportManagerImpl (feature:library commonMain, previously Android-only).

// IosNoOpReadaloudSidecarDownloads / IosNoOpReadaloudSidecarPrefetcher removed (issue #1065):
// iOS now binds IosReadaloudSidecarStore, which caches the /synced bundle minus its audio
// (ADR 0040) on the survivable scope and serves both the prefetch and downloads-listing roles.
