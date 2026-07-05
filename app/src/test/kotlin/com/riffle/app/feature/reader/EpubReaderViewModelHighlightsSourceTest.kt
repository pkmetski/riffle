package com.riffle.app.feature.reader

import android.net.FakeUri
import com.riffle.app.feature.reader.highlights.HighlightsPublicationFactory
import com.riffle.core.database.AnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Url

/**
 * [EpubReaderViewModel] cannot be constructed directly in a JVM unit test — it's an
 * [androidx.lifecycle.AndroidViewModel] with Readium dependencies that route through
 * `android.net.Uri`, unmocked under the stock (non-Robolectric) Android test stub jar this module
 * uses (see [EpubReaderViewModelTest]'s file-level comment for the same constraint). Per Task 7's
 * brief, the grouping/build logic that `loadHighlightsPublication` delegates to has been extracted
 * into the top-level `internal fun buildChapterElisions` in EpubReaderViewModel.kt, so this test
 * exercises that function directly, then feeds its output through the *real*
 * [HighlightsPublicationFactory] (same JVM Url-fixture seam as [com.riffle.app.feature.reader.highlights.HighlightsPublicationFactoryTest]) to
 * pin the behaviour Task 7 actually demands: Highlights-mode loads a synthesised Publication whose
 * readingOrder contains one link per chapter with highlights — not the ABS EPUB's full spine.
 */
class EpubReaderViewModelHighlightsSourceTest {

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

    private fun highlight(
        id: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double = 0.0,
        createdAt: Long = 0L,
        deleted: Boolean = false,
        type: String = AnnotationEntity.TYPE_HIGHLIGHT,
    ): AnnotationEntity = AnnotationEntity(
        id = id,
        serverId = "S1",
        itemId = "B1",
        type = type,
        cfi = "epubcfi(/6/${(spineIndex + 1) * 2}!/dummy)",
        textSnippet = "snippet-$id",
        chapterHref = chapterHref,
        spineIndex = spineIndex,
        progression = progression,
        createdAt = createdAt,
        updatedAt = createdAt,
        originDeviceId = "test",
        lastModifiedByDeviceId = "test",
        deleted = deleted,
    )

    // The regression this pins: three highlights across two chapters must produce a Publication
    // with readingOrder.size == 2 (one per chapter WITH highlights), not the ABS EPUB's full spine
    // (which a fake EpubRepository that throws on open proves is never consulted in this path).
    @Test
    fun `Highlights mode publication reading order has one link per chapter with highlights`() {
        val rows = listOf(
            highlight("h1", "chA.xhtml", spineIndex = 0, progression = 0.5, createdAt = 3L),
            highlight("h2", "chA.xhtml", spineIndex = 0, progression = 0.1, createdAt = 1L),
            highlight("h3", "chB.xhtml", spineIndex = 2, progression = 0.2, createdAt = 2L),
            // Noise the grouping must exclude: a bookmark and a soft-deleted highlight.
            highlight("b1", "chA.xhtml", spineIndex = 0, type = AnnotationEntity.TYPE_BOOKMARK),
            highlight("h4", "chC.xhtml", spineIndex = 5, deleted = true),
        )

        val chapters = buildChapterElisions(rows)
        val pub = HighlightsPublicationFactory().build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = chapters,
            urlFactory = ::testUrlFactory,
        )

        assertEquals(2, pub.readingOrder.size)
        assertEquals(listOf("chA", "chB"), pub.tableOfContents.map { it.title })
    }

    // Within a chapter, highlights must render in reading-position order (progression), not
    // insertion/createdAt order — chA's h2 (progression 0.1) must precede h1 (progression 0.5).
    @Test
    fun `highlights within a chapter are ordered by progression not createdAt`() {
        val rows = listOf(
            highlight("h1", "chA.xhtml", spineIndex = 0, progression = 0.5, createdAt = 1L),
            highlight("h2", "chA.xhtml", spineIndex = 0, progression = 0.1, createdAt = 2L),
        )

        val chapters = buildChapterElisions(rows)

        assertEquals(1, chapters.size)
        assertEquals(listOf("h2", "h1"), chapters.single().highlights.map { it.id })
    }

    @Test
    fun `deriveChapterTitle strips directory and extension`() {
        assertEquals("ch03", deriveChapterTitle("OEBPS/ch03.xhtml"))
        assertEquals("Chapter", deriveChapterTitle(""))
    }
}
