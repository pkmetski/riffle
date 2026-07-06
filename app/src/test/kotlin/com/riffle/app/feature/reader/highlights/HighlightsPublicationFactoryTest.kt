package com.riffle.app.feature.reader.highlights

import android.net.FakeUri
import com.riffle.app.feature.reader.toCssRgba
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.HighlightColor
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

    // Fix A regression: the paragraph's visual paint must come from inline CSS, not solely from
    // Readium's text-matched decoration — a long or punctuated snippet can fail that match and
    // leave the paragraph unpainted (see HighlightsPublicationFactory's KDoc on highlightBackgroundCss).
    // This pins that the rendered <p> always carries a background-color style, keyed off the
    // highlight's own color token via the single-source HighlightColor palette.
    @Test
    fun highlightParagraphCarriesInlineBackgroundColorFromItsColorToken() {
        val pub = factory.build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision(
                    "ch1.xhtml",
                    "Chapter One",
                    listOf(hl("h1", "a long punctuated snippet. With a sentence boundary.", color = "green")),
                ),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        val expectedCss = HighlightColor.fromToken("green").argb.toCssRgba()
        assertTrue(
            "expected <p> to carry background-color: $expectedCss, got: $html",
            html.contains("class=\"riffle-hl\" data-ann-id=\"h1\" style=\"background-color: $expectedCss;\""),
        )
    }

    // Unknown/unrecognised color tokens must not leave the paragraph unpainted — they fall back to
    // HighlightColor.DEFAULT (yellow), mirroring HighlightColor.fromToken's own fallback contract.
    @Test
    fun unknownColorTokenFallsBackToDefaultYellowBackground() {
        val pub = factory.build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(hl("h1", "snippet", color = "not-a-real-color"))),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        val expectedCss = HighlightColor.DEFAULT.argb.toCssRgba()
        assertTrue(html.contains("background-color: $expectedCss;"))
    }

    // Regression for the "text is very small in Highlights mode" bug: Readium's ReadiumCss only
    // auto-injects ReadiumCSS-default.css (font-size baseline, heading type-scale, flow spacing)
    // when the resource has NO publisher-supplied styling — and it treats our highlight's
    // inline `style="background-color:...` (Fix A, kept for guaranteed-visible painting) as
    // publisher styling, so it silently skips that injection. Without an explicit fallback link,
    // the synthesised chapter would render at ~unstyled browser default size instead of matching
    // FullBook mode's Formatting Preferences. See HighlightsPublicationFactory's
    // READIUM_DEFAULT_CSS_LINK KDoc for the full mechanism.
    @Test
    fun rendersDefaultReadiumCssLinkSoTypographyMatchesFullBookMode() {
        val pub = factory.build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(hl("h1", "snippet"))),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(
            "expected a <link> to Readium's own ReadiumCSS-default.css in <head>, got: $html",
            html.contains(
                "<link rel=\"stylesheet\" type=\"text/css\" " +
                    "href=\"https://readium_assets/readium/readium-css/ReadiumCSS-default.css\"/>",
            ),
        )
    }

    // Regression for "Immersive Mode doesn't toggle in the elided reader": with zero paragraph
    // margin (ReadiumCSS-default.css's own default), adjacent highlight <p>s sit flush against
    // each other, so Readium's decoration hit-test (sized to the full text line) covers virtually
    // the entire reading surface and Android.onTap never fires. Each highlight <p> needs an
    // explicit margin so a real, non-decorated gap exists between highlights for the immersive
    // tap target. See HighlightsPublicationFactory's PARAGRAPH_GAP_STYLE KDoc for the full
    // mechanism.
    @Test
    fun highlightParagraphCarriesExplicitMarginForImmersiveTapGap() {
        val pub = factory.build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision(
                    "ch1.xhtml",
                    "Chapter One",
                    listOf(hl("h1", "first snippet"), hl("h2", "second snippet")),
                ),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(
            "expected every highlight <p> to carry an explicit non-zero margin, got: $html",
            html.contains("<p style=\"margin: 1em 0;\">"),
        )
    }

    // Notes need their own paler/neutral background so they read as visually distinct from the
    // highlight paragraph above them.
    @Test
    fun noteAsideCarriesNeutralBackground() {
        val pub = factory.build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(hl("h1", "snippet", note = "my thought"))),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(html.contains("<aside class=\"riffle-note\" data-ann-id=\"h1\" style=\"background-color: #f5f5f5;\">"))
    }

    private fun hl(
        id: String,
        snippet: String,
        note: String? = null,
        spineIndex: Int = 0,
        progression: Double = 0.0,
        color: String = AnnotationEntity.COLOR_YELLOW,
    ): AnnotationEntity =
        AnnotationEntity(
            id = id,
            serverId = "S1",
            itemId = "B1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/${(spineIndex + 1) * 2}!/dummy)",
            textSnippet = snippet,
            note = note,
            color = color,
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
