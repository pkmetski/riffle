package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.ComicArchive
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A null bitmap while the decode is running must render a loading indicator — the previous
 * behaviour fed the null straight into Coil, which resolves a null model as an instant empty
 * error, so the user saw a fully blank page for the whole multi-second streaming fetch. Once the
 * decode settles without a bitmap, the page must show an error, never an infinite spinner.
 */
class CbzPageContentTest {

    @Test fun `null bitmap while decoding renders the loading indicator, not a blank page`() {
        assertEquals(CbzPageContent.Loading, cbzPageContent(null, decodeSettled = false))
    }

    @Test fun `null bitmap after the decode settled renders an error, not an infinite spinner`() {
        assertEquals(CbzPageContent.Error, cbzPageContent(null, decodeSettled = true))
    }

    // --- retry budget per source ---

    private class FakeArchive : ComicArchive {
        override val pageCount = 1
        override fun imageBytes(pageIndex: Int): ByteArray = ByteArray(0)
        override fun openStream(pageIndex: Int): InputStream = ByteArray(0).inputStream()
        override fun mediaType(pageIndex: Int): String = "image/jpeg"
        override fun close() {}
    }

    @Test fun `local archive decodes never retry - failure is deterministic`() {
        assertEquals(1, decodeAttemptsFor(ArchiveImageSource(FakeArchive())))
    }

    @Test fun `streaming decodes retry - network failure is transient`() {
        val source = NetworkImageSource("s", "i", 1, repository = FailingCbzRepository)
        assertEquals(3, decodeAttemptsFor(source))
    }
}

private object FailingCbzRepository : com.riffle.core.domain.CbzRepository {
    override suspend fun openCbz(item: com.riffle.core.models.LibraryItem) = error("unused")
    override suspend fun downloadCbz(
        item: com.riffle.core.models.LibraryItem,
        onProgress: (Long, Long) -> Unit,
    ) = error("unused")
    override suspend fun removeDownload(sourceId: String, itemId: String) = error("unused")
    override fun isDownloaded(sourceId: String, itemId: String) = false
    override fun isCached(sourceId: String, itemId: String) = false
    override suspend fun saveReadingPosition(itemId: String, locatorJson: String) = error("unused")
    override suspend fun supportsStreaming(sourceId: String) = true
    override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray =
        error("unused")
    override suspend fun awaitCachedFile(item: com.riffle.core.models.LibraryItem): java.io.File? = null
}
