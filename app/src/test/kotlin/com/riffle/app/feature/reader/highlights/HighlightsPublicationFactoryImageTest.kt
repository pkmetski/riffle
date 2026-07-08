package com.riffle.app.feature.reader.highlights

import android.net.FakeUri
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.EmbeddedFigure
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
    ): AnnotationEntity = AnnotationEntity(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = AnnotationEntity.TYPE_IMAGE,
        cfi = "epubcfi(/6/2!/dummy)",
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
    ): AnnotationEntity = AnnotationEntity(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/2!/dummy)",
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
