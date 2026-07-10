package com.riffle.app.feature.reader.highlights

import android.net.FakeUri
import com.riffle.app.feature.reader.toCssRgba
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.EmbeddedFigure
import com.riffle.core.domain.HighlightColor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Url

/**
 * Pure-JVM tests for [HighlightsPublicationFactory]'s Task 9 figure rendering: TYPE_IMAGE
 * annotations rendered as full-size `<figure>` blocks, TYPE_HIGHLIGHT's `embeddedFigures`
 * interleaved after the highlight text, and the [ResourceFetcher] seam populating the synthetic
 * container with fetched image bytes.
 *
 * [fakeUrl] mirrors [HighlightsPublicationFactoryTest.testUrlFactory] — see that class's KDoc for
 * why JVM tests can't use the real `Url(String)` factory.
 */
class HighlightsPublicationFactoryImageTest {
    private val factory = HighlightsPublicationFactory()

    @Suppress("UNCHECKED_CAST")
    private fun fakeUrl(href: String): Url {
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

    private fun readChapterHtml(pub: Publication, index: Int = 0): String {
        val link = pub.readingOrder[index]
        val resource = pub.get(link) ?: error("no resource for $link")
        val bytes = runBlocking { resource.read().getOrNull() ?: error("read failed") }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun imageAnnotation(
        id: String = "img1",
        imageHref: String? = null,
        imageSvg: String? = null,
        imageBytes: String? = null,
        caption: String = "",
        color: String = AnnotationEntity.COLOR_YELLOW,
    ): AnnotationEntity = AnnotationEntity(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = AnnotationEntity.TYPE_IMAGE,
        cfi = "epubcfi(/6/2!/dummy)",
        color = color,
        textSnippet = caption,
        chapterHref = "ch0.xhtml",
        imageHref = imageHref,
        imageSvg = imageSvg,
        imageBytes = imageBytes,
        createdAt = 0L,
        updatedAt = 0L,
        originDeviceId = "test",
        lastModifiedByDeviceId = "test",
    )

    private fun highlightAnnotation(
        id: String = "hl1",
        textSnippet: String,
        embedded: List<EmbeddedFigure>,
        color: String = AnnotationEntity.COLOR_YELLOW,
    ): AnnotationEntity = AnnotationEntity(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/2!/dummy)",
        color = color,
        textSnippet = textSnippet,
        chapterHref = "ch0.xhtml",
        embeddedFigures = embeddedFiguresJson(embedded),
        createdAt = 0L,
        updatedAt = 0L,
        originDeviceId = "test",
        lastModifiedByDeviceId = "test",
    )

    private fun embeddedFiguresJson(figures: List<EmbeddedFigure>): String =
        Json.encodeToString(ListSerializer(EmbeddedFigure.serializer()), figures)

    @Test
    fun `TYPE_IMAGE annotation with imageBytes renders inline data-URI img and caption`() {
        val dataUri = "data:image/jpeg;base64,/9j/4AAQ=="
        val ann = imageAnnotation(imageHref = "images/g.png", imageBytes = dataUri, caption = "Fig 1")
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        assertTrue(html.contains("<figure"))
        assertTrue(html.contains("src=\"data:image/jpeg;base64,"))
        assertTrue(html.contains("<figcaption>Fig 1</figcaption>"))
    }

    @Test
    fun `legacy TYPE_IMAGE annotation without imageBytes emits placeholder not broken img`() {
        // Reverting this test would let the synthetic/… <img> back in, which crashes Readium's
        // WebViewServer with an NPE on Url.relativize in the elided reader.
        val ann = imageAnnotation(imageHref = "images/g.png", caption = "Fig 1")
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        assertTrue(html.contains("<figure"))
        assertTrue(html.contains("[figure image not captured]"))
        assertTrue(!html.contains("src=\"synthetic/figures/"))
    }

    @Test
    fun `TYPE_IMAGE with imageSvg inlines svg source verbatim`() {
        val ann = imageAnnotation(imageSvg = "<svg><rect/></svg>", caption = "Diagram")
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        assertTrue(html.contains("<svg><rect/></svg>"))
    }

    // Regression pin for the "Url.relativize NPE" crash (see sanitizeSvgForElidedView's KDoc).
    // Before this sanitizer, an inline SVG that contained an external `<image xlink:href="…">` or
    // `<use href="…">` would embed verbatim into the elided XHTML; Chromium then requested that
    // href against the elided publication's package URL, WebViewServer failed to relativize, and
    // the reader process crashed.
    @Test
    fun `TYPE_IMAGE with imageSvg strips external image and use references before embedding`() {
        val svg = """<svg><image xlink:href="../OEBPS/foo.png"/><use href="assets.svg#icon"/><rect/></svg>"""
        val ann = imageAnnotation(imageSvg = svg, caption = "Diagram")
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        assertTrue("external <image> stripped", !html.contains("xlink:href=\"../OEBPS/foo.png\""))
        assertTrue("external <use> stripped", !html.contains("assets.svg#icon"))
        assertTrue("safe SVG content preserved", html.contains("<rect"))
    }

    // Data-URI hrefs must NOT be stripped — they bypass WebViewServer entirely and are safe.
    @Test
    fun `TYPE_IMAGE with imageSvg preserves data-URI image references`() {
        val svg = """<svg><image xlink:href="data:image/png;base64,AAA"/><rect/></svg>"""
        val ann = imageAnnotation(imageSvg = svg, caption = "Diagram")
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        assertTrue(html.contains("data:image/png;base64,AAA"))
    }

    @Test
    fun `TYPE_HIGHLIGHT with embeddedFigures renders text then caption placeholders in order`() {
        val ann = highlightAnnotation(
            textSnippet = "highlighted text",
            embedded = listOf(
                EmbeddedFigure(href = "a.png", svg = null, caption = "figure A", order = 0),
                EmbeddedFigure(href = "b.png", svg = null, caption = "figure B", order = 1),
            ),
        )
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        // Embedded figures don't carry captured bytes (only the parent highlight's range walk knows
        // about them), so all we emit is caption + placeholder — order-preserved via `order`.
        val text = html.indexOf("highlighted text")
        val a = html.indexOf("figure A")
        val b = html.indexOf("figure B")
        assertTrue(text >= 0 && a >= 0 && b >= 0)
        assertTrue(text < a && a < b)
    }

    /**
     * Fix #3 (2026-07-09): annotated figures must render centered, not left-justified. The chapter
     * head injects [FIGURE_CENTERING_CSS] which centers `.riffle-fig` via `margin: 1em auto` and
     * centers its inner `<img>`/`<svg>` via `display:block; margin:0 auto`. Would flip red if
     * either rule were dropped or renamed.
     */
    @Test
    fun `elided chapter head includes centering CSS for riffle-fig`() {
        val ann = imageAnnotation(imageBytes = "data:image/png;base64,AA==", caption = "Fig 1")
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        assertTrue("riffle-fig must be centered", html.contains(".riffle-fig{margin:1em auto"))
        assertTrue(
            "inner image must be block-centered",
            html.contains(".riffle-fig>img,.riffle-fig>svg{display:block;margin:0 auto"),
        )
    }

    /**
     * Fix #4 (2026-07-09): an annotated graph must show the same vertical coloured accent bar the
     * text highlights get. The `<figure>` carries an inline `border-left: 4px solid <color>` and
     * a nested [ACCENT_BAR_TAP_CLASS] span for tap-to-open dispatch. Reverting either the border
     * or the tap span would flip the corresponding assertion.
     */
    @Test
    fun `annotated figure has coloured border-left accent bar and tap span`() {
        val ann = imageAnnotation(
            imageBytes = "data:image/png;base64,AA==",
            caption = "Fig 1",
            color = HighlightColor.YELLOW.token,
        )
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        val yellowRgba = HighlightColor.YELLOW.argb.toCssRgba()
        assertTrue(
            "figure must carry coloured border-left in its inline style",
            html.contains("<figure class=\"riffle-fig\" data-ann-id=\"${ann.id}\" style=\"border-left: 4px solid $yellowRgba"),
        )
        assertTrue(
            "figure must carry the tap-dispatch span with the annotation id",
            html.contains("class=\"$ACCENT_BAR_TAP_CLASS\" data-ann-id=\"${ann.id}\""),
        )
    }

    /**
     * Fix #4 embedded-figure variant: a figure appended after a TYPE_HIGHLIGHT inherits the
     * OWNING highlight's colour and id — a tap on the embedded figure's accent bar opens the
     * highlight's popup, not a phantom separate annotation editor.
     */
    @Test
    fun `embedded figure inherits owning highlight's colour and id on its accent bar`() {
        val ann = highlightAnnotation(
            id = "hl-1",
            color = HighlightColor.BLUE.token,
            textSnippet = "highlighted text",
            embedded = listOf(
                EmbeddedFigure(href = "a.png", svg = null, caption = "figure A", order = 0),
            ),
        )
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        val blueRgba = HighlightColor.BLUE.argb.toCssRgba()
        val figureBlockStart = html.indexOf("<figure class=\"riffle-fig\"")
        assertTrue("embedded figure block must exist", figureBlockStart >= 0)
        val figureBlock = html.substring(figureBlockStart)
        assertTrue(
            "embedded figure must inherit owner's colour on border-left",
            figureBlock.startsWith("<figure class=\"riffle-fig\" data-ann-id=\"hl-1\" style=\"border-left: 4px solid $blueRgba"),
        )
        assertTrue(
            "embedded figure's tap span must carry owning-highlight id",
            figureBlock.contains("class=\"$ACCENT_BAR_TAP_CLASS\" data-ann-id=\"hl-1\""),
        )
    }

    /**
     * Fix 2026-07-09 (text-before-figure, figure, text-after-figure): with charOffset populated on
     * the embedded figure, the elided view splits the highlight's snippet at the figure's true
     * position instead of dumping the figure at the end. Regression pin against the "text-then-
     * figure" v1 behaviour re-emerging.
     */
    @Test
    fun `highlight with embedded figure charOffset renders text-figure-text in DOM order`() {
        val ann = highlightAnnotation(
            id = "hl-1",
            textSnippet = "beforeafter",
            embedded = listOf(
                EmbeddedFigure(
                    href = "img.png",
                    svg = null,
                    caption = "Fig",
                    order = 0,
                    imageBytes = "data:image/png;base64,AA==",
                    charOffset = 6L,
                ),
            ),
        )
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        val beforeIdx = html.indexOf(">before</span></p>")
        val figIdx = html.indexOf("<figure class=\"riffle-fig\"")
        val afterIdx = html.indexOf(">after</span></p>")
        assertTrue("`before` chunk must exist as its own <p>", beforeIdx >= 0)
        assertTrue("figure must exist", figIdx >= 0)
        assertTrue("`after` chunk must exist as its own <p>", afterIdx >= 0)
        assertTrue("order must be before < figure < after", beforeIdx < figIdx && figIdx < afterIdx)
    }

    /**
     * Legacy embedded figures written before `charOffset` existed have `charOffset = null`. The
     * elided view must fall back to the v1 "text first, then figures" rendering rather than
     * split the snippet at an unknown position. Reverting the null-guard would flip red because
     * the figure would land BEFORE the text (via `splitSnippetForFiguresAt`'s clamp-to-length).
     */
    @Test
    fun `highlight with embedded figure lacking charOffset falls back to text-then-figure`() {
        val ann = highlightAnnotation(
            id = "hl-2",
            textSnippet = "highlighted text",
            embedded = listOf(
                EmbeddedFigure(
                    href = "img.png",
                    svg = null,
                    caption = "Fig",
                    order = 0,
                    imageBytes = "data:image/png;base64,AA==",
                    charOffset = null,
                ),
            ),
        )
        val pub = factory.build(
            "S1",
            "B1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
        )
        val html = readChapterHtml(pub)
        val text = html.indexOf(">highlighted text</span></p>")
        val fig = html.indexOf("<figure class=\"riffle-fig\"")
        assertTrue("text must appear as one whole <p> (no split)", text >= 0)
        assertTrue("figure must exist", fig >= 0)
        assertTrue("legacy fallback: text then figure", text < fig)
    }

    @Test
    fun `factory populates container with fetched image bytes`() {
        val ann = imageAnnotation(imageHref = "images/g.png")
        val bytes = byteArrayOf(1, 2, 3)
        val fetcher = ResourceFetcher { if (it == "images/g.png") bytes else null }
        val pub = factory.build(
            "s1",
            "i1",
            "Book",
            listOf(ChapterElision("ch1.xhtml", "One", listOf(ann))),
            urlFactory = ::fakeUrl,
            resourceFetcher = fetcher,
        )
        val url = fakeUrl("synthetic/figures/images_g.png")
        val resource = pub.get(url) ?: error("no resource for synthetic figure path")
        val served = runBlocking { resource.read().getOrNull() }
        assertArrayEquals(bytes, served)
    }
}
