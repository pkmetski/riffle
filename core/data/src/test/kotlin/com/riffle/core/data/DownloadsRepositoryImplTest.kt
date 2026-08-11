package com.riffle.core.data

import com.riffle.core.domain.DefaultDispatcherProvider
import com.riffle.core.domain.StoredItemRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class DownloadsRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `lists audiobook downloads and caches alongside file backed stores`() = runTest {
        val stores = stores()
        stores.epubDownloads.save("srv", "epub-down", ByteArrayInputStream("epub".toByteArray()))
        stores.epubCache.save("srv", "epub-cache", ByteArrayInputStream("cache".toByteArray()))
        writeAudiobook(stores.audiobookDownloadsDir, "srv", "audio-down", "downloaded-track")
        writeAudiobook(stores.audiobookCacheDir, "srv", "audio-cache", "cached-track")

        val repo = repo(stores)

        assertEquals(
            setOf(
                StoredItemRef("srv", "epub-down"),
                StoredItemRef("srv", "audio-down"),
            ),
            repo.getDownloadedItems().toSet(),
        )
        assertEquals(
            setOf(
                StoredItemRef("srv", "epub-cache"),
                StoredItemRef("srv", "audio-cache"),
            ),
            repo.getCachedItems().toSet(),
        )
    }

    @Test
    fun `removeDownload clears matching cache entries so items do not reappear as cached`() = runTest {
        val stores = stores()
        stores.epubDownloads.save("srv", "ebook", ByteArrayInputStream("download".toByteArray()))
        stores.epubCache.save("srv", "ebook", ByteArrayInputStream("cache".toByteArray()))
        writeAudiobook(stores.audiobookDownloadsDir, "srv", "audio", "downloaded-track")
        writeAudiobook(stores.audiobookCacheDir, "srv", "audio", "cached-track")
        val repo = repo(stores)

        repo.removeDownload("srv", "ebook")
        repo.removeDownload("srv", "audio")

        assertTrue(repo.getDownloadedItems().none { it.sourceId == "srv" && it.itemId in setOf("ebook", "audio") })
        assertTrue(repo.getCachedItems().none { it.sourceId == "srv" && it.itemId in setOf("ebook", "audio") })
        assertFalse(stores.audiobookDownloadsDir.resolve("srv").resolve("audio").exists())
        assertFalse(stores.audiobookCacheDir.resolve("srv").resolve("audio").exists())
    }

    @Test
    fun `size and removals include directory backed audiobook artifacts`() = runTest {
        val stores = stores()
        writeAudiobook(stores.audiobookDownloadsDir, "srv", "audio-down", "downloaded-track")
        writeAudiobook(stores.audiobookCacheDir, "srv", "audio-cache", "cached-track")

        val repo = repo(stores)
        val downloadDir = stores.audiobookDownloadsDir.resolve("srv").resolve("audio-down")
        val cacheDir = stores.audiobookCacheDir.resolve("srv").resolve("audio-cache")

        assertEquals(directorySize(downloadDir), repo.sizeOf("srv", "audio-down"))
        assertEquals(directorySize(cacheDir), repo.sizeOf("srv", "audio-cache"))

        repo.removeDownload("srv", "audio-down")
        repo.removeCached("srv", "audio-cache")

        assertFalse(downloadDir.exists())
        assertFalse(cacheDir.exists())

        writeAudiobook(stores.audiobookDownloadsDir, "srv", "audio-down-2", "downloaded-track")
        writeAudiobook(stores.audiobookCacheDir, "srv", "audio-cache-2", "cached-track")

        repo.removeAllDownloads()
        repo.clearAllCached()

        assertTrue(stores.audiobookDownloadsDir.listFiles().isNullOrEmpty())
        assertTrue(stores.audiobookCacheDir.listFiles().isNullOrEmpty())
    }

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun writeAudiobook(root: File, sourceId: String, itemId: String, trackPayload: String) {
        val dir = root.resolve(sourceId).resolve(itemId).also { it.mkdirs() }
        dir.resolve("track-0").writeText(trackPayload)
        dir.resolve("manifest.json").writeText("""{"tracks":[{"index":0,"file":"track-0"}],"chapters":[],"durationSec":1.0}""")
    }

    private fun repo(stores: TestStores): DownloadsRepositoryImpl = DownloadsRepositoryImpl(
        epubCacheStore = stores.epubCache,
        epubDownloadsStore = stores.epubDownloads,
        pdfCacheStore = stores.pdfCache,
        pdfDownloadsStore = stores.pdfDownloads,
        cbzCacheStore = stores.cbzCache,
        cbzDownloadsStore = stores.cbzDownloads,
        audiobookCacheDir = stores.audiobookCacheDir,
        audiobookDownloadsDir = stores.audiobookDownloadsDir,
    )

    private fun stores(): TestStores = TestStores(
        epubCache = store("epub-cache"),
        epubDownloads = store("epub-downloads"),
        pdfCache = store("pdf-cache", ".pdf"),
        pdfDownloads = store("pdf-downloads", ".pdf"),
        cbzCache = store("cbz-cache", ".cbz"),
        cbzDownloads = store("cbz-downloads", ".cbz"),
        audiobookCacheDir = tmp.newFolder("audiobook-cache"),
        audiobookDownloadsDir = tmp.newFolder("audiobook-downloads"),
    )

    private fun store(name: String, extension: String = ".epub") =
        LocalStoreImpl(tmp.newFolder(name), extension, DefaultDispatcherProvider)

    private data class TestStores(
        val epubCache: LocalStoreImpl,
        val epubDownloads: LocalStoreImpl,
        val pdfCache: LocalStoreImpl,
        val pdfDownloads: LocalStoreImpl,
        val cbzCache: LocalStoreImpl,
        val cbzDownloads: LocalStoreImpl,
        val audiobookCacheDir: File,
        val audiobookDownloadsDir: File,
    )
}
