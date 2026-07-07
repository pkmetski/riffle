package com.riffle.app.feature.reader.cadence

import com.riffle.core.domain.SentenceQuote
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DomSentenceSourceTest {

    @Test
    fun `loadAll and chapterHrefs suspend until supplyResult`() = runTest {
        val source = DomSentenceSource()
        val loadAllJob = async { source.loadAll() }
        val hrefsJob = async { source.chapterHrefs() }

        val quotes = mapOf(
            "chapter1.xhtml#cd-0" to SentenceQuote(before = "", highlight = "First sentence.", after = ""),
            "chapter1.xhtml#cd-1" to SentenceQuote(before = "", highlight = "Second one.", after = ""),
        )
        val hrefs = mapOf(
            "chapter1.xhtml#cd-0" to "chapter1.xhtml",
            "chapter1.xhtml#cd-1" to "chapter1.xhtml",
        )
        source.supplyResult(quotes, hrefs)

        assertEquals(quotes, loadAllJob.await())
        assertEquals(hrefs, hrefsJob.await())
    }

    @Test
    fun `supplyEmpty completes both maps as empty (Intl-Segmenter-missing WebView)`() = runTest {
        // Regression: when the WebView reports no `Intl.Segmenter` support, DomSentenceSource must
        // NOT hang — Cadence's session build calls supplyEmpty() and the top-bar icon simply
        // never appears. If this ever suspended, the reader would stall waiting for Cadence.
        val source = DomSentenceSource()
        source.supplyEmpty()
        assertEquals(emptyMap<String, SentenceQuote>(), source.loadAll())
        assertEquals(emptyMap<String, String>(), source.chapterHrefs())
    }

    @Test
    fun `second supplyResult call is ignored (idempotent per-book build)`() = runTest {
        val source = DomSentenceSource()
        val first = mapOf("c#cd-0" to SentenceQuote(before = "", highlight = "A", after = ""))
        source.supplyResult(first, mapOf("c#cd-0" to "c"))
        // A second call must not overwrite the first — matches CompletableDeferred.complete()'s
        // "first wins" semantics; per-book means one build.
        source.supplyResult(
            mapOf("c#cd-9" to SentenceQuote(before = "", highlight = "Z", after = "")),
            mapOf("c#cd-9" to "c"),
        )
        assertEquals(first, source.loadAll())
    }
}
