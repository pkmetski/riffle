package com.riffle.core.domain

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryItemOfflineAvailabilityCommonTest {

    private fun item(
        ebookFormat: EbookFormat,
        hasAudio: Boolean = false,
        sourceId: String = "s1",
        id: String = "i1",
    ) = LibraryItem(
        id = id,
        libraryId = "lib",
        title = "t",
        author = "a",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = ebookFormat,
        hasAudio = hasAudio,
        sourceId = sourceId,
    )

    private fun availability(
        epubRepository: EpubRepository = FakeEpubRepository(),
        pdfRepository: PdfRepository = FakePdfRepository(),
        cbzRepository: CbzRepository = FakeCbzRepository(),
        audiobookDownloadRepository: AudiobookDownloadRepository = FakeAudiobookDownloadRepository(),
        bundleAudiobookSource: BundleAudiobookSource = FakeBundleAudiobookSource(),
    ) = LibraryItemOfflineAvailabilityImpl(
        epubRepository = epubRepository,
        pdfRepository = pdfRepository,
        cbzRepository = cbzRepository,
        audiobookDownloadRepository = audiobookDownloadRepository,
        bundleAudiobookSource = bundleAudiobookSource,
        clock = TestClock(),
    )

    @Test
    fun epubThatIsDownloadedIsAvailableOffline() {
        assertTrue(availability(epubRepository = FakeEpubRepository(downloaded = true)).isAvailableOffline(item(EbookFormat.Epub)))
    }

    @Test
    fun epubThatIsOnlyCachedIsAvailableOffline() {
        assertTrue(availability(epubRepository = FakeEpubRepository(cached = true)).isAvailableOffline(item(EbookFormat.Epub)))
    }

    @Test
    fun pdfThatIsDownloadedIsAvailableOffline() {
        assertTrue(availability(pdfRepository = FakePdfRepository(downloaded = true)).isAvailableOffline(item(EbookFormat.Pdf)))
    }

    @Test
    fun downloadedAudiobookOnlyItemIsAvailableOffline() {
        assertTrue(
            availability(audiobookDownloadRepository = FakeAudiobookDownloadRepository(downloaded = true))
                .isAvailableOffline(item(EbookFormat.Unsupported, hasAudio = true)),
        )
    }

    @Test
    fun itemWithNothingDownloadedOrCachedIsNotAvailableOffline() {
        assertFalse(availability().isAvailableOffline(item(EbookFormat.Epub, hasAudio = true)))
    }

    @Test
    fun itemWithNoEbookOrAudiobookDownloadIsOfflineAvailableWhenBundleIsDownloaded() {
        assertTrue(
            availability(bundleAudiobookSource = FakeBundleAudiobookSource(setOf("s1/i1")))
                .isAvailableOffline(item(EbookFormat.Unsupported, hasAudio = true)),
        )
    }

    @Test
    fun itemWithNoDownloadsAndNoBundleIsNotOfflineAvailable() {
        assertFalse(
            availability(bundleAudiobookSource = FakeBundleAudiobookSource(emptySet()))
                .isAvailableOffline(item(EbookFormat.Unsupported, hasAudio = true)),
        )
    }

    private class FakeEpubRepository(
        private val downloaded: Boolean = false,
        private val cached: Boolean = false,
    ) : EpubRepository {
        override fun isDownloaded(sourceId: String, itemId: String) = downloaded
        override fun isCached(sourceId: String, itemId: String) = cached
        override suspend fun downloadEpub(
            item: LibraryItem,
            onProgress: (downloaded: Long, total: Long) -> Unit,
        ) = error("unused")
        override suspend fun removeDownload(sourceId: String, itemId: String) = error("unused")
        override suspend fun saveReadingPosition(sourceId: String, itemId: String, cfi: String) = error("unused")
    }

    private class FakePdfRepository(
        private val downloaded: Boolean = false,
        private val cached: Boolean = false,
    ) : PdfRepository {
        override fun isDownloaded(sourceId: String, itemId: String) = downloaded
        override fun isCached(sourceId: String, itemId: String) = cached
        override suspend fun downloadPdf(
            item: LibraryItem,
            onProgress: (downloaded: Long, total: Long) -> Unit,
        ) = error("unused")
        override suspend fun removeDownload(sourceId: String, itemId: String) = error("unused")
        override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) = error("unused")
    }

    private class FakeCbzRepository(
        private val downloaded: Boolean = false,
        private val cached: Boolean = false,
    ) : CbzRepository {
        override fun isDownloaded(sourceId: String, itemId: String) = downloaded
        override fun isCached(sourceId: String, itemId: String) = cached
        override suspend fun openCbz(item: LibraryItem) = error("unused")
        override suspend fun downloadCbz(
            item: LibraryItem,
            onProgress: (downloaded: Long, total: Long) -> Unit,
        ) = error("unused")
        override suspend fun removeDownload(sourceId: String, itemId: String) = error("unused")
        override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) = error("unused")
        override suspend fun supportsStreaming(sourceId: String) = false
        override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?) = error("unused")
        override suspend fun awaitCachedSource(item: LibraryItem): CbzLocalSource? = null
    }

    private class FakeAudiobookDownloadRepository(
        private val downloaded: Boolean = false,
    ) : AudiobookDownloadRepository {
        override fun isDownloaded(sourceId: String, itemId: String) = downloaded
        override suspend fun download(
            sourceId: String,
            itemId: String,
            onProgress: (downloaded: Long, total: Long) -> Unit,
        ) = error("unused")
        override suspend fun remove(sourceId: String, itemId: String) = error("unused")
    }

    private class FakeBundleAudiobookSource(
        private val offlineIds: Set<String> = emptySet(),
    ) : BundleAudiobookSource {
        override suspend fun localSession(sourceId: String, itemId: String): AudiobookSession? = null
        override fun isAvailableOffline(sourceId: String, itemId: String) = "$sourceId/$itemId" in offlineIds
    }
}
