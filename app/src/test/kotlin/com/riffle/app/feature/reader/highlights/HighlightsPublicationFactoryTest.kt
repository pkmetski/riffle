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
            sourceId = "S1",
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
            sourceId = "S1",
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
            sourceId = "S1",
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

    // The highlight's visual is a left accent bar in the palette colour (matching Riffle's
    // [Book Search] results card style), NOT a full paragraph background — the constant background
    // was fatiguing on dense chapters. This pins that the rendered <p> carries a border-left in
    // the highlight's own colour, keyed off the single-source HighlightColor palette.
    @Test
    fun highlightParagraphCarriesLeftAccentBarFromItsColorToken() {
        val pub = factory.build(
            sourceId = "S1",
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
            "expected <p> to carry border-left: 4px solid $expectedCss, got: $html",
            html.contains("border-left: 4px solid $expectedCss !important;"),
        )
        assertTrue(
            "expected the <span data-ann-id> to have no inline background, got: $html",
            !html.contains("class=\"riffle-hl\" data-ann-id=\"h1\" style="),
        )
    }

    // Unknown/unrecognised color tokens must not leave the paragraph unaccented — they fall back
    // to HighlightColor.DEFAULT (yellow), mirroring HighlightColor.fromToken's own fallback contract.
    @Test
    fun unknownColorTokenFallsBackToDefaultYellowAccent() {
        val pub = factory.build(
            sourceId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(hl("h1", "snippet", color = "not-a-real-color"))),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        val expectedCss = HighlightColor.DEFAULT.argb.toCssRgba()
        assertTrue(html.contains("border-left: 4px solid $expectedCss !important;"))
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
            sourceId = "S1",
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
            sourceId = "S1",
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
            html.contains("<p style=\"margin: 1em 0;"),
        )
    }

    // The accent-bar tap span owns tap dispatch in Highlights mode: taps on the visible left
    // border-left land on this transparent absolute-positioned span (see ACCENT_BAR_TAP_CSS),
    // whose onclick navigates to a riffle://annotation-tap/<id> URL that both continuous
    // (ChapterWebView.shouldOverrideUrlLoading) and paginated/vertical (EpubReaderScreen
    // .onExternalLinkActivated) intercept. Reverting the accent-bar tap element would let taps
    // land on the highlighted text again, reintroducing the pre-2026-07 behaviour where any
    // on-text tap opened the highlight menu.
    @Test
    fun highlightParagraphCarriesAccentBarTapSpanTargetingRiffleUrl() {
        val pub = factory.build(
            sourceId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(hl("h1", "the spice must flow"))),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(
            "expected a tap span with data-ann-id=h1 in the emitted HTML, got: $html",
            html.contains("<span class=\"riffle-hl-tap\" data-ann-id=\"h1\""),
        )
        assertTrue(
            "tap span must navigate to the riffle:// scheme parsed by parseAnnotationTapUrl, got: $html",
            html.contains("location.href='riffle://annotation-tap/h1?l='+x+"),
        )
        // Raw XHTML: `&` in attribute values is escaped as `&amp;`. When the parser hands the
        // string to JS at page load time it turns back into `&`, so the emitted URL is
        // `?l=<L>&t=<T>&r=<R>&b=<B>`. Position uses `event.clientX/clientY` (the actual tap
        // point, WebView-viewport CSS px) so the popup anchors right where the user touched
        // rather than at the paragraph's top edge — the tap span itself spans the whole `<p>`,
        // which would otherwise anchor the popup a full paragraph away for a multi-line highlight.
        assertTrue(
            "tap span must anchor the popup to event.clientX/Y, not to the whole element's rect; " +
                "got: $html",
            html.contains("var e=event,x=e.clientX,y=e.clientY;") &&
                html.contains("'&amp;t='+y+'&amp;r='+(x+1)+'&amp;b='+(y+1)"),
        )
        assertTrue(
            "the tap span's absolute positioning only works when the parent <p> is position: relative",
            html.contains("position: relative"),
        )
        assertTrue(
            "the tap CSS class must be defined in a <style> block so the tap zone has a size",
            html.contains(".riffle-hl-tap{position:absolute;"),
        )
    }

    // The percent-encoding path must survive an id that contains characters (spaces, `#`, `?`)
    // Uri would otherwise treat as URL syntax. The synthesised HTML embeds the id twice — once in
    // data-ann-id (XML-escaped) and once in the onclick's URL (URL-encoded) — so the emitted URL
    // must be the URL-encoded form. Pairing this pin with AnnotationTapUrlTest's round-trip means
    // reverting either half fails one or the other.
    @Test
    fun accentBarTapSpanUrlEncodesTheAnnotationId() {
        val pub = factory.build(
            sourceId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(hl("has space", "text"))),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(
            "id `has space` must be url-encoded in the tap URL, got: $html",
            html.contains("location.href='riffle://annotation-tap/has+space?") ||
                html.contains("location.href='riffle://annotation-tap/has%20space?"),
        )
    }

    // Notes need their own paler/neutral background so they read as visually distinct from the
    // highlight paragraph above them.
    @Test
    fun noteAsideCarriesNeutralBackground() {
        val pub = factory.build(
            sourceId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(hl("h1", "snippet", note = "my thought"))),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(html.contains("<aside class=\"riffle-note\" data-ann-id=\"h1\" style=\"border-left: 2px solid "))
        assertTrue(html.contains("font-style: italic; opacity: 0.75;\">my thought</aside>"))
    }

    // Issue #484: an annotation with a captured `originFontFamily` renders its excerpt `<p>` with
    // an inline `font-family` declaration, so the elided view matches the face the reader saw at
    // the source range instead of falling back to ReadiumCSS's default serif.
    @Test
    fun originFontFamily_isInlinedOnExcerptParagraph() {
        val pub = factory.build(
            sourceId = "S1", itemId = "B1", bookTitle = null,
            chapters = listOf(
                ChapterElision(
                    "ch0.xhtml", "One",
                    listOf(hl("h1", "sans excerpt", originFontFamily = "\"Fira Sans\", sans-serif")),
                ),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(
            "excerpt <p> carries an inline font-family with the captured value; html was: $html",
            html.contains("font-family: &quot;Fira Sans&quot;, sans-serif !important;")
        )
    }

    // When the annotation has no captured font, the caller-supplied publication-wide body-font
    // is used as fallback. Preserves the "match the book, at least approximately" experience for
    // legacy rows written before issue #484 shipped.
    @Test
    fun bookBodyFontFamily_fallsBackWhenAnnotationHasNoOwnFont() {
        val pub = factory.build(
            sourceId = "S1", itemId = "B1", bookTitle = null,
            chapters = listOf(
                ChapterElision(
                    "ch0.xhtml", "One",
                    listOf(hl("h1", "legacy row", originFontFamily = null)),
                ),
            ),
            urlFactory = ::testUrlFactory,
            bookBodyFontFamily = "Georgia, serif",
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(
            "excerpt <p> falls back to book body font; html was: $html",
            html.contains("font-family: Georgia, serif !important;")
        )
    }

    // The `bookBodyFontFamily` is applied via a `<style>` block that targets `<body>` + every
    // heading tag so the chapter title, notes, and figcaptions inherit the origin's face —
    // matches "the elided view must inherit the origin's font" for the whole document, not just
    // excerpts. `!important` is load-bearing because ReadiumCSS-default.css sets its own
    // font-family on headings via `--RS__*Font*` CSS variables.
    @Test
    fun bookBodyFontFamily_isAppliedToHeadingsAndAsideViaStyleBlock() {
        val pub = factory.build(
            sourceId = "S1", itemId = "B1", bookTitle = null,
            chapters = listOf(
                ChapterElision(
                    "ch0.xhtml", "Chapter Title Here",
                    listOf(hl("h1", "excerpt", note = "my note")),
                ),
            ),
            urlFactory = ::testUrlFactory,
            bookBodyFontFamily = "Georgia, serif",
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(
            "<style> block must set the book body font on body + headings + aside; html was: $html",
            html.contains(
                "body, h1, h2, h3, h4, h5, h6, aside, figcaption, .riffle-fig " +
                    "{ font-family: Georgia, serif !important; }"
            )
        )
    }

    // Both null → no font-family declaration at all → ReadiumCSS's default applies. Preserves
    // the pre-issue-484 behaviour for callers that don't (yet) supply a fallback.
    @Test
    fun noFontFamilyDeclaredWhenBothAnnotationAndBookAreNull() {
        val pub = factory.build(
            sourceId = "S1", itemId = "B1", bookTitle = null,
            chapters = listOf(
                ChapterElision("ch0.xhtml", "One", listOf(hl("h1", "no font"))),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue("no inline font-family emitted", !html.contains("font-family:"))
    }

    // A font-family value whose bytes contain something outside the safe allowlist (e.g. an
    // injected `};color:red;`) must be dropped, not written into the excerpt style attribute —
    // the DB is not a trust boundary and CSS injection must not be possible.
    @Test
    fun maliciousFontFamily_isDroppedNotWritten() {
        val pub = factory.build(
            sourceId = "S1", itemId = "B1", bookTitle = null,
            chapters = listOf(
                ChapterElision(
                    "ch0.xhtml", "One",
                    listOf(hl("h1", "hi", originFontFamily = "serif;} body{color:red")),
                ),
            ),
            urlFactory = ::testUrlFactory,
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(
            "malicious font-family value must be dropped; html was: $html",
            !html.contains("body{color:red") && !html.contains("font-family:")
        )
    }

    private fun hl(
        id: String,
        snippet: String,
        note: String? = null,
        spineIndex: Int = 0,
        progression: Double = 0.0,
        color: String = AnnotationEntity.COLOR_YELLOW,
        originFontFamily: String? = null,
    ): AnnotationEntity =
        AnnotationEntity(
            id = id,
            sourceId = "S1",
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
            originFontFamily = originFontFamily,
        )

    private fun readChapterHtml(pub: Publication, index: Int): String {
        val link = pub.readingOrder[index]
        val resource = pub.get(link) ?: error("no resource for $link")
        val bytes = runBlocking { resource.read().getOrNull() ?: error("read failed") }
        return bytes.toString(Charsets.UTF_8)
    }
}
