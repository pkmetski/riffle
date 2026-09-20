package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.StoredArtifactDownloadsRepository
import com.riffle.core.domain.StoredMediaType

/**
 * iOS host for the shared [StoredArtifactDownloadsRepository] policy.
 *
 * It scans the same eight namespaces Android's `DownloadsRepositoryImpl` does — EPUB, PDF and CBZ
 * downloads and caches plus the two directory-backed audiobook roots — so the Downloads screen
 * sees every format instead of EPUBs only. Everything else (cached net of downloaded, removing a
 * download also removing the hidden cache copy, notifying [LocalAvailabilityEvents]) comes from
 * `core:domain`.
 */
class IosDownloadsRepositoryImpl(
    fileStore: FileStore,
    localAvailabilityEvents: LocalAvailabilityEvents,
) : DownloadsRepository by StoredArtifactDownloadsRepository(
    downloadStores = listOf(
        IosFileArtifactStore(fileStore, NS_EPUB_DOWNLOADS, ".epub", StoredMediaType.Epub),
        IosFileArtifactStore(fileStore, NS_PDF_DOWNLOADS, ".pdf", StoredMediaType.Pdf),
        IosFileArtifactStore(fileStore, NS_CBZ_DOWNLOADS, ".cbz", StoredMediaType.Cbz),
        IosAudiobookArtifactStore(fileStore, NS_AUDIOBOOK_DOWNLOADS),
    ),
    cacheStores = listOf(
        IosFileArtifactStore(fileStore, NS_EPUB_CACHE, ".epub", StoredMediaType.Epub),
        IosFileArtifactStore(fileStore, NS_PDF_CACHE, ".pdf", StoredMediaType.Pdf),
        IosFileArtifactStore(fileStore, NS_CBZ_CACHE, ".cbz", StoredMediaType.Cbz),
        IosAudiobookArtifactStore(fileStore, NS_AUDIOBOOK_CACHE),
    ),
    localAvailabilityEvents = localAvailabilityEvents,
)
