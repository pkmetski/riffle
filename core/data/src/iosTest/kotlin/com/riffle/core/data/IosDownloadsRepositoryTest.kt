package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.data.AudiobookFilenames.MANIFEST
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.domain.StoredMediaType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS counterpart to core:data's androidHostTest `DownloadsRepositoryImplTest` (#1071 §12),
 * driving the real iOS code path: NSFileManager directory enumeration over the eight
 * download/cache namespaces.
 *
 * Before the fix this class scanned only `epub-downloads`/`epub-cache`, so every PDF, CBZ and
 * audiobook on disk was invisible to the Downloads screen.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDownloadsRepositoryTest {

    private val roots = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    @Test
    fun listsPdfCbzAndAudiobookArtifactsAlongsideEpubs() {
        val store = TempFileStore()
        writeFile(store, NS_EPUB_DOWNLOADS, "src/epub-down.epub")
        writeFile(store, NS_PDF_DOWNLOADS, "src/pdf-down.pdf")
        writeFile(store, NS_CBZ_DOWNLOADS, "src/cbz-down.cbz")
        writeAudiobook(store, NS_AUDIOBOOK_DOWNLOADS, "src", "audio-down")
        writeFile(store, NS_EPUB_CACHE, "src/epub-cache.epub")
        writeFile(store, NS_PDF_CACHE, "src/pdf-cache.pdf")
        writeFile(store, NS_CBZ_CACHE, "src/cbz-cache.cbz")
        writeAudiobook(store, NS_AUDIOBOOK_CACHE, "src", "audio-cache")

        val repo = IosDownloadsRepositoryImpl(store, RecordingAvailabilityEvents())

        assertEquals(
            setOf(
                StoredItemArtifact("src", "epub-down", StoredMediaType.Epub),
                StoredItemArtifact("src", "pdf-down", StoredMediaType.Pdf),
                StoredItemArtifact("src", "cbz-down", StoredMediaType.Cbz),
                StoredItemArtifact("src", "audio-down", StoredMediaType.Audiobook),
            ),
            repo.getDownloadedArtifacts().toSet(),
        )
        assertEquals(
            setOf(
                StoredItemArtifact("src", "epub-cache", StoredMediaType.Epub),
                StoredItemArtifact("src", "pdf-cache", StoredMediaType.Pdf),
                StoredItemArtifact("src", "cbz-cache", StoredMediaType.Cbz),
                StoredItemArtifact("src", "audio-cache", StoredMediaType.Audiobook),
            ),
            repo.getCachedArtifacts().toSet(),
        )
    }

    @Test
    fun cachedListExcludesDownloadedItemsSoNothingIsCountedTwice() {
        val store = TempFileStore()
        writeFile(store, NS_EPUB_DOWNLOADS, "src/book.epub", payload = "download")
        writeFile(store, NS_EPUB_CACHE, "src/book.epub", payload = "cache")

        val repo = IosDownloadsRepositoryImpl(store, RecordingAvailabilityEvents())

        assertEquals(listOf(StoredItemRef("src", "book")), repo.getDownloadedItems())
        assertTrue(repo.getCachedItems().isEmpty(), "a downloaded item must not also show up as cached")
    }

    @Test
    fun removeDownloadDeletesTheCacheCopyAndNotifiesAvailability() = runTest {
        val store = TempFileStore()
        writeFile(store, NS_EPUB_DOWNLOADS, "src/book.epub", payload = "download")
        writeFile(store, NS_EPUB_CACHE, "src/book.epub", payload = "cache")
        writeAudiobook(store, NS_AUDIOBOOK_DOWNLOADS, "src", "audio")
        writeAudiobook(store, NS_AUDIOBOOK_CACHE, "src", "audio")
        val events = RecordingAvailabilityEvents()

        val repo = IosDownloadsRepositoryImpl(store, events)
        repo.removeDownload("src", "book")
        repo.removeDownload("src", "audio")

        assertTrue(repo.getDownloadedItems().isEmpty())
        assertTrue(repo.getCachedItems().isEmpty(), "the hidden cache copy must go with the download")
        assertFalse(exists(store, NS_EPUB_CACHE, "src/book.epub"))
        assertFalse(exists(store, NS_AUDIOBOOK_CACHE, "src/audio/$MANIFEST"))
        assertEquals(listOf("src" to "book", "src" to "audio"), events.notified)
    }

    @Test
    fun sizeOfSumsDownloadAndCacheBytesAcrossNamespaces() {
        val store = TempFileStore()
        writeFile(store, NS_PDF_DOWNLOADS, "src/book.pdf", payload = "1234567890")
        writeFile(store, NS_PDF_CACHE, "src/book.pdf", payload = "12345")

        val repo = IosDownloadsRepositoryImpl(store, RecordingAvailabilityEvents())

        assertEquals(15L, repo.sizeOf("src", "book"))
    }

    @Test
    fun removeCachedNotifiesAndLeavesDownloadsAlone() = runTest {
        val store = TempFileStore()
        writeFile(store, NS_CBZ_DOWNLOADS, "src/other.cbz")
        writeFile(store, NS_CBZ_CACHE, "src/comic.cbz")
        val events = RecordingAvailabilityEvents()

        val repo = IosDownloadsRepositoryImpl(store, events)
        repo.removeCached("src", "comic")

        assertTrue(repo.getCachedItems().isEmpty())
        assertEquals(listOf(StoredItemRef("src", "other")), repo.getDownloadedItems())
        assertEquals(listOf("src" to "comic"), events.notified)
    }

    @Test
    fun clearAllCachedAndRemoveAllDownloadsSweepEveryNamespace() = runTest {
        val store = TempFileStore()
        writeFile(store, NS_EPUB_DOWNLOADS, "src/e.epub")
        writeFile(store, NS_PDF_DOWNLOADS, "src/p.pdf")
        writeAudiobook(store, NS_AUDIOBOOK_DOWNLOADS, "src", "a")
        writeFile(store, NS_CBZ_CACHE, "src/c.cbz")
        writeAudiobook(store, NS_AUDIOBOOK_CACHE, "src", "b")

        val repo = IosDownloadsRepositoryImpl(store, RecordingAvailabilityEvents())
        repo.removeAllDownloads()
        repo.clearAllCached()

        assertTrue(repo.getDownloadedArtifacts().isEmpty())
        assertTrue(repo.getCachedArtifacts().isEmpty())
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private fun writeFile(store: FileStore, namespace: String, relativePath: String, payload: String = "x") {
        val path = store.resolve(namespace, relativePath)
        IosAudiobookFiles.mkdirs(path.substringBeforeLast('/'))
        assertTrue(IosAudiobookFiles.writeText(path, payload), "could not write $path")
    }

    private fun writeAudiobook(store: FileStore, namespace: String, sourceId: String, itemId: String) {
        val dir = store.resolve(namespace, "$sourceId/$itemId")
        IosAudiobookFiles.mkdirs(dir)
        IosAudiobookFiles.writeText("$dir/track-0", "track")
        IosAudiobookFiles.writeText("$dir/$MANIFEST", "{}")
    }

    private fun exists(store: FileStore, namespace: String, relativePath: String): Boolean =
        IosAudiobookFiles.exists(store.resolve(namespace, relativePath))

    /** FileStore rooted at a fresh temp dir so nothing touches the real app directories. */
    private inner class TempFileStore : FileStore {
        private val root = NSTemporaryDirectory() + "downloads_test_" + NSUUID().UUIDString()

        init {
            roots += root
        }

        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            IosAudiobookFiles.mkdirs(base)
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }
    }

    private class RecordingAvailabilityEvents : LocalAvailabilityEvents {
        val notified = mutableListOf<Pair<String, String>>()
        private val _changes = MutableSharedFlow<StoredItemRef>(extraBufferCapacity = 8)
        override val changes: SharedFlow<StoredItemRef> get() = _changes
        override fun notifyChanged(sourceId: String, itemId: String) {
            notified += sourceId to itemId
        }
    }
}
