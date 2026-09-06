package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.ComicPageSource
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
        assertEquals(CbzPageContent.Loading, cbzPageContent(hasBitmap = false, decodeSettled = false))
    }

    @Test fun `null bitmap after the decode settled renders an error, not an infinite spinner`() {
        assertEquals(CbzPageContent.Error, cbzPageContent(hasBitmap = false, decodeSettled = true))
    }

    @Test fun `decoded bitmap with panels still resolving renders loading, never the unpositioned image`() {
        // Rendering the bitmap before panel detection lands would show it at Identity and then
        // jump to the panel transform — the spurious pan. The gate must hold on Loading.
        assertEquals(
            CbzPageContent.Loading,
            cbzPageContent(hasBitmap = true, decodeSettled = true, panelsReady = false),
        )
    }

    @Test fun `decoded bitmap with panels ready renders the image`() {
        assertEquals(
            CbzPageContent.Image,
            cbzPageContent(hasBitmap = true, decodeSettled = true, panelsReady = true),
        )
    }

    // --- retry budget per source ---

    /** Local archive-backed source: deterministic decode, no retries. */
    private class LocalPageSource : ComicPageSource {
        override val pageCount = 1
        override fun imageBytes(pageIndex: Int): ByteArray = ByteArray(0)
        override fun mediaType(pageIndex: Int): String = "image/jpeg"
    }

    /** Network-streaming source: transient failures warrant retries. */
    private class StreamingPageSource : ComicPageSource {
        override val pageCount = 1
        override fun imageBytes(pageIndex: Int): ByteArray = ByteArray(0)
        override fun mediaType(pageIndex: Int): String = "image/jpeg"
        override val decodeRetries = 3
    }

    @Test fun `local archive decodes never retry - failure is deterministic`() {
        assertEquals(1, decodeAttemptsFor(LocalPageSource()))
    }

    @Test fun `streaming decodes retry - network failure is transient`() {
        assertEquals(3, decodeAttemptsFor(StreamingPageSource()))
    }
}
