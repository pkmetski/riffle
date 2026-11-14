package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.data.AudiobookFilenames.MANIFEST
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.domain.StoredMediaType
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.sink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadsRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileStore(root: File): FileStore = object : FileStore {
        override fun resolve(namespace: String, relativePath: String): String {
            val base = root.resolve(namespace)
            return if (relativePath.isEmpty()) {
                base.apply { mkdirs() }.absolutePath
            } else {
                File(base, relativePath).also { it.parentFile?.mkdirs() }.absolutePath
            }
        }
        override fun exists(namespace: String, relativePath: String) =
            File(resolve(namespace, relativePath)).exists()
    }

    private fun repo(root: File): DownloadsRepositoryImpl = DownloadsRepositoryImpl(fileStore(root))

    private fun writeEpub(root: File, namespace: String, sourceId: String, itemId: String) {
        root.resolve(namespace).resolve(sourceId).resolve("$itemId.epub").also { it.parentFile?.mkdirs() }.writeText("epub-content")
    }

    private fun writePdf(root: File, namespace: String, sourceId: String, itemId: String) {
        root.resolve(namespace).resolve(sourceId).resolve("$itemId.pdf").also { it.parentFile?.mkdirs() }.writeText("pdf-content")
    }

    private fun writeCbz(root: File, namespace: String, sourceId: String, itemId: String) {
        root.resolve(namespace).resolve(sourceId).resolve("$itemId.cbz").also { it.parentFile?.mkdirs() }.writeText("cbz-content")
    }

    private fun writeAudiobook(root: File, namespace: String, sourceId: String, itemId: String, trackPayload: String) {
        val dir = root.resolve(namespace).resolve(sourceId).resolve(itemId).also { it.mkdirs() }
        dir.resolve("track-0").writeText(trackPayload)
        dir.resolve(MANIFEST).writeText("""{"tracks":[{"index":0,"file":"track-0"}],"chapters":[],"durationSec":1.0}""")
    }

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    @Test
    fun `lists audiobook downloads and caches alongside file backed stores`() = runTest {
        val root = tmp.newFolder()
        writeEpub(root, NS_EPUB_DOWNLOADS, "srv", "epub-down")
        writePdf(root, NS_PDF_DOWNLOADS, "srv", "pdf-down")
        writeCbz(root, NS_CBZ_DOWNLOADS, "srv", "cbz-down")
        writeEpub(root, NS_EPUB_CACHE, "srv", "epub-cache")
        writePdf(root, NS_PDF_CACHE, "srv", "pdf-cache")
        writeCbz(root, NS_CBZ_CACHE, "srv", "cbz-cache")
        writeAudiobook(root, NS_AUDIOBOOK_DOWNLOADS, "srv", "audio-down", "downloaded-track")
        writeAudiobook(root, NS_AUDIOBOOK_CACHE, "srv", "audio-cache", "cached-track")

        val repo = repo(root)

        assertEquals(
            setOf(
                StoredItemRef("srv", "epub-down"),
                StoredItemRef("srv", "pdf-down"),
                StoredItemRef("srv", "cbz-down"),
                StoredItemRef("srv", "audio-down"),
            ),
            repo.getDownloadedItems().toSet(),
        )
        assertEquals(
            setOf(
                StoredItemRef("srv", "epub-cache"),
                StoredItemRef("srv", "pdf-cache"),
                StoredItemRef("srv", "cbz-cache"),
                StoredItemRef("srv", "audio-cache"),
            ),
            repo.getCachedItems().toSet(),
        )
        assertEquals(
            setOf(
                StoredItemArtifact("srv", "epub-down", StoredMediaType.Epub),
                StoredItemArtifact("srv", "pdf-down", StoredMediaType.Pdf),
                StoredItemArtifact("srv", "cbz-down", StoredMediaType.Cbz),
                StoredItemArtifact("srv", "audio-down", StoredMediaType.Audiobook),
            ),
            repo.getDownloadedArtifacts().toSet(),
        )
        assertEquals(
            setOf(
                StoredItemArtifact("srv", "epub-cache", StoredMediaType.Epub),
                StoredItemArtifact("srv", "pdf-cache", StoredMediaType.Pdf),
                StoredItemArtifact("srv", "cbz-cache", StoredMediaType.Cbz),
                StoredItemArtifact("srv", "audio-cache", StoredMediaType.Audiobook),
            ),
            repo.getCachedArtifacts().toSet(),
        )
    }

    @Test
    fun `removeDownload clears matching cache entries so items do not reappear as cached`() = runTest {
        val root = tmp.newFolder()
        writeEpub(root, NS_EPUB_DOWNLOADS, "srv", "ebook")
        writeEpub(root, NS_EPUB_CACHE, "srv", "ebook")
        writeAudiobook(root, NS_AUDIOBOOK_DOWNLOADS, "srv", "audio", "downloaded-track")
        writeAudiobook(root, NS_AUDIOBOOK_CACHE, "srv", "audio", "cached-track")
        val repo = repo(root)

        repo.removeDownload("srv", "ebook")
        repo.removeDownload("srv", "audio")

        assertTrue(repo.getDownloadedItems().none { it.sourceId == "srv" && it.itemId in setOf("ebook", "audio") })
        assertTrue(repo.getCachedItems().none { it.sourceId == "srv" && it.itemId in setOf("ebook", "audio") })
        assertFalse(root.resolve(NS_AUDIOBOOK_DOWNLOADS).resolve("srv").resolve("audio").exists())
        assertFalse(root.resolve(NS_AUDIOBOOK_CACHE).resolve("srv").resolve("audio").exists())
    }

    @Test
    fun `size and removals include directory backed audiobook artifacts`() = runTest {
        val root = tmp.newFolder()
        writeAudiobook(root, NS_AUDIOBOOK_DOWNLOADS, "srv", "audio-down", "downloaded-track")
        writeAudiobook(root, NS_AUDIOBOOK_CACHE, "srv", "audio-cache", "cached-track")

        val repo = repo(root)
        val downloadDir = root.resolve(NS_AUDIOBOOK_DOWNLOADS).resolve("srv").resolve("audio-down")
        val cacheDir = root.resolve(NS_AUDIOBOOK_CACHE).resolve("srv").resolve("audio-cache")

        assertEquals(directorySize(downloadDir), repo.sizeOf("srv", "audio-down"))
        assertEquals(directorySize(cacheDir), repo.sizeOf("srv", "audio-cache"))

        repo.removeDownload("srv", "audio-down")
        repo.removeCached("srv", "audio-cache")

        assertFalse(downloadDir.exists())
        assertFalse(cacheDir.exists())

        writeAudiobook(root, NS_AUDIOBOOK_DOWNLOADS, "srv", "audio-down-2", "downloaded-track")
        writeAudiobook(root, NS_AUDIOBOOK_CACHE, "srv", "audio-cache-2", "cached-track")

        repo.removeAllDownloads()
        repo.clearAllCached()

        assertTrue(root.resolve(NS_AUDIOBOOK_DOWNLOADS).listFiles().isNullOrEmpty())
        assertTrue(root.resolve(NS_AUDIOBOOK_CACHE).listFiles().isNullOrEmpty())
    }
}
