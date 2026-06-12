package com.riffle.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryItemOfflineAvailabilityTest {

    private fun item(
        ebookFormat: EbookFormat,
        hasAudio: Boolean = false,
        serverId: String = "s1",
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
        serverId = serverId,
    )

    @Test
    fun `epub that is downloaded is available offline`() {
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(downloaded = true),
            pdfRepository = FakePdfRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
            bundleAudiobookSource = FakeBundleAudiobookSource(),
        )

        assertTrue(availability.isAvailableOffline(item(EbookFormat.Epub)))
    }

    @Test
    fun `epub that is only cached is available offline`() {
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(cached = true),
            pdfRepository = FakePdfRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
            bundleAudiobookSource = FakeBundleAudiobookSource(),
        )

        assertTrue(availability.isAvailableOffline(item(EbookFormat.Epub)))
    }

    @Test
    fun `pdf that is downloaded is available offline`() {
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(),
            pdfRepository = FakePdfRepository(downloaded = true),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
            bundleAudiobookSource = FakeBundleAudiobookSource(),
        )

        assertTrue(availability.isAvailableOffline(item(EbookFormat.Pdf)))
    }

    @Test
    fun `downloaded audiobook-only item is available offline`() {
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(),
            pdfRepository = FakePdfRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(downloaded = true),
            bundleAudiobookSource = FakeBundleAudiobookSource(),
        )

        assertTrue(availability.isAvailableOffline(item(EbookFormat.Unsupported, hasAudio = true)))
    }

    @Test
    fun `matched item with only the audiobook downloaded is available offline`() {
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(), // ebook not downloaded/cached
            pdfRepository = FakePdfRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(downloaded = true),
            bundleAudiobookSource = FakeBundleAudiobookSource(),
        )

        assertTrue(availability.isAvailableOffline(item(EbookFormat.Epub, hasAudio = true)))
    }

    @Test
    fun `item with nothing downloaded or cached is not available offline`() {
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(),
            pdfRepository = FakePdfRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
            bundleAudiobookSource = FakeBundleAudiobookSource(),
        )

        assertFalse(availability.isAvailableOffline(item(EbookFormat.Epub, hasAudio = true)))
    }

    @Test
    fun `item with no ebook or audiobook download is offline-available when its bundle is downloaded`() {
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(),
            pdfRepository = FakePdfRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
            bundleAudiobookSource = FakeBundleAudiobookSource(setOf("s1/i1")),
        )

        assertTrue(availability.isAvailableOffline(item(EbookFormat.Unsupported, hasAudio = true)))
    }

    @Test
    fun `item with no downloads and no bundle is not offline-available`() {
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(),
            pdfRepository = FakePdfRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
            bundleAudiobookSource = FakeBundleAudiobookSource(emptySet()),
        )

        assertFalse(availability.isAvailableOffline(item(EbookFormat.Unsupported, hasAudio = true)))
    }

    private class FakeEpubRepository(
        private val downloaded: Boolean = false,
        private val cached: Boolean = false,
    ) : EpubRepository {
        override fun isDownloaded(serverId: String, itemId: String) = downloaded
        override fun isCached(serverId: String, itemId: String) = cached
        override suspend fun openEpub(item: LibraryItem) = error("unused")
        override suspend fun downloadEpub(
            item: LibraryItem,
            onProgress: (downloaded: Long, total: Long) -> Unit,
        ) = error("unused")
        override suspend fun removeDownload(serverId: String, itemId: String) = error("unused")
        override suspend fun saveReadingPosition(itemId: String, cfi: String) = error("unused")
    }

    private class FakePdfRepository(
        private val downloaded: Boolean = false,
        private val cached: Boolean = false,
    ) : PdfRepository {
        override fun isDownloaded(serverId: String, itemId: String) = downloaded
        override fun isCached(serverId: String, itemId: String) = cached
        override suspend fun openPdf(item: LibraryItem) = error("unused")
        override suspend fun downloadPdf(
            item: LibraryItem,
            onProgress: (downloaded: Long, total: Long) -> Unit,
        ) = error("unused")
        override suspend fun removeDownload(serverId: String, itemId: String) = error("unused")
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) = error("unused")
    }

    private class FakeAudiobookDownloadRepository(
        private val downloaded: Boolean = false,
    ) : AudiobookDownloadRepository {
        override fun isDownloaded(serverId: String, itemId: String) = downloaded
        override fun localSession(serverId: String, itemId: String): AudiobookSession? = null
        override suspend fun download(
            serverId: String,
            itemId: String,
            onProgress: (downloaded: Long, total: Long) -> Unit,
        ) = error("unused")
        override suspend fun remove(serverId: String, itemId: String) = error("unused")
    }

    private class FakeBundleAudiobookSource(
        private val offlineIds: Set<String> = emptySet(),
    ) : BundleAudiobookSource {
        override suspend fun localSession(serverId: String, itemId: String): AudiobookSession? = null
        override fun isAvailableOffline(serverId: String, itemId: String) = "$serverId/$itemId" in offlineIds
    }
}
