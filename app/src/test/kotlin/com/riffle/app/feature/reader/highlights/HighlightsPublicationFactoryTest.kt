package com.riffle.app.feature.reader.highlights

import android.net.FakeUri
import com.riffle.core.database.AnnotationEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Url

/**
 * Pure-JVM tests for [HighlightsPublicationFactory] — the synthesised [Publication] behind the
 * elided reader (ADR 0041).
 *
 * [testUrlFactory] stands in for [HighlightsPublicationFactory]'s default `Url(String)` argument:
 * `Url(String)` funnels through `android.net.Uri.parse`, which is unmocked under the stock
 * (non-Robolectric) Android unit-test stub jar used by this module (same constraint documented on
 * [com.riffle.app.feature.reader.presenter.ReadiumPresenterTest] and
 * [com.riffle.app.feature.reader.session.PositionOrchestratorTest]). We build a [RelativeUrl] via
 * `Unsafe.allocateInstance` + `android.net.FakeUri` instead, exactly like those tests' fixture
 * helpers, so the actual factory logic under test (spine filtering, HTML rendering, container
 * wiring, TOC titles) runs unmodified.
 *
 * Interpretation note on the third test's name (`chapterTitleFallsBackToHrefBasenameThenChapterN`):
 * the factory itself does not compute any title fallback — it renders [ChapterElision.title]
 * verbatim into the TOC and chapter heading. The "basename, then Chapter N" fallback logic is
 * `EpubReaderViewModel.loadHighlightsPublication`'s responsibility (Task 7); this factory's
 * contract is only that whatever title string the caller supplies survives unchanged into
 * [Publication.tableOfContents]. The fixture below deliberately passes titles that *look like*
 * fallback output ("ch2", "Chapter 2") to pin that pass-through behaviour.
 */
class HighlightsPublicationFactoryTest {
    private val factory = HighlightsPublicationFactory()

    @Suppress("UNCHECKED_CAST")
    private fun testUrlFactory(href: String): Url {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val url = unsafe.allocateInstance(RelativeUrl::class.java) as RelativeUrl
        RelativeUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
            .set(url, FakeUri(href))
        return url
    }

    @Test
    fun spineOnlyIncludesChaptersWithHighlights() {
        val pub = factory.build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = "Dune",
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(hl("h1", "the spice must flow"))),
                ChapterElision("ch3.xhtml", "Chapter Three", listOf(hl("h2", "Fear is the mind-killer."))),
            ),
            urlFactory = ::testUrlFactory,
        )
        assertEquals(2, pub.readingOrder.size)
        assertEquals(listOf("Chapter One", "Chapter Three"), pub.tableOfContents.map { it.title })
    }

    @Test
    fun rendersHighlightsAndInlineNotesInCfiOrder() {
        val pub = factory.build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision(
                    "ch1.xhtml",
                    "Chapter One",
                    listOf(
                        hl("h1", "first snippet", note = "my thought"),
                        hl("h2", "second snippet", note = null),
                    ),
                ),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(html.indexOf("first snippet") < html.indexOf("my thought"))
        assertTrue(html.indexOf("my thought") < html.indexOf("second snippet"))
        assertTrue("notes render as <aside>", "<aside" in html)
    }

    @Test
    fun chapterTitleFallsBackToHrefBasenameThenChapterN() {
        val pub = factory.build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision("ch2.xhtml", title = "ch2", listOf(hl("h1", "x"))),
                ChapterElision("", title = "Chapter 2", listOf(hl("h2", "y"))),
            ),
            urlFactory = ::testUrlFactory,
        )
        assertEquals(listOf("ch2", "Chapter 2"), pub.tableOfContents.map { it.title })
    }

    private fun hl(
        id: String,
        snippet: String,
        note: String? = null,
        spineIndex: Int = 0,
        progression: Double = 0.0,
    ): AnnotationEntity =
        AnnotationEntity(
            id = id,
            serverId = "S1",
            itemId = "B1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/${(spineIndex + 1) * 2}!/dummy)",
            textSnippet = snippet,
            note = note,
            chapterHref = "ch$spineIndex.xhtml",
            spineIndex = spineIndex,
            progression = progression,
            createdAt = 0L,
            updatedAt = 0L,
            originDeviceId = "test",
            lastModifiedByDeviceId = "test",
        )

    private fun readChapterHtml(pub: Publication, index: Int): String {
        val link = pub.readingOrder[index]
        val resource = pub.get(link) ?: error("no resource for $link")
        val bytes = runBlocking { resource.read().getOrNull() ?: error("read failed") }
        return bytes.toString(Charsets.UTF_8)
    }
}
