package com.riffle.core.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ServerFilesCleanerImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(n: Int) = ByteArrayInputStream(ByteArray(n))

    @Test
    fun `deletes every store's files for the removed server but keeps other servers`() = runTest {
        val epubDir = tmp.newFolder("epubs")
        val pdfDir = tmp.newFolder("pdfs")
        val audiobookDir = tmp.newFolder("audiobooks")

        val epubStore = LocalStoreImpl(epubDir, ".epub")
        val pdfStore = LocalStoreImpl(pdfDir, ".pdf")

        // Two servers, each with an EPUB, a PDF, and an audiobook download directory.
        epubStore.save("srv-A", "book-1", bytes(10))
        epubStore.save("srv-B", "book-1", bytes(10))
        pdfStore.save("srv-A", "doc-1", bytes(10))
        pdfStore.save("srv-B", "doc-1", bytes(10))
        File(audiobookDir, "srv-A/audio-1").apply { mkdirs() }.let { File(it, "track-0").writeBytes(ByteArray(10)) }
        File(audiobookDir, "srv-B/audio-1").apply { mkdirs() }.let { File(it, "track-0").writeBytes(ByteArray(10)) }

        val cleaner = ServerFilesCleanerImpl(
            stores = listOf(epubStore, pdfStore),
            audiobookDownloadsDir = audiobookDir,
        )

        cleaner.deleteAllForServer("srv-A")

        // srv-A is gone from every store...
        assertFalse(File(epubDir, "srv-A").exists())
        assertFalse(File(pdfDir, "srv-A").exists())
        assertFalse(File(audiobookDir, "srv-A").exists())
        // ...and srv-B is untouched.
        assertTrue(File(epubDir, "srv-B/book-1.epub").exists())
        assertTrue(File(pdfDir, "srv-B/doc-1.pdf").exists())
        assertTrue(File(audiobookDir, "srv-B/audio-1/track-0").exists())
    }

    @Test
    fun `is a no-op when the server has no files`() = runTest {
        val epubDir = tmp.newFolder("epubs")
        val audiobookDir = tmp.newFolder("audiobooks")
        val cleaner = ServerFilesCleanerImpl(
            stores = listOf(LocalStoreImpl(epubDir, ".epub")),
            audiobookDownloadsDir = audiobookDir,
        )

        cleaner.deleteAllForServer("ghost")

        assertFalse(File(epubDir, "ghost").exists())
    }
}
