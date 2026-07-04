package com.riffle.app.feature.reader

import android.graphics.Rect
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator

class ChapterWebViewBinderTest {

    /**
     * [Rect]'s 4-arg constructor is a no-op under the plain-JVM android.jar unit-test stub (it
     * doesn't populate the fields — only the default no-arg constructor + field writes work), so
     * every [Rect] in this test is built through field assignment via this helper instead.
     */
    private fun rectOf(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect().apply { this.left = left; this.top = top; this.right = right; this.bottom = bottom }

    /** Hand-written fake driving [ChapterWebViewLike] without a live Android WebView. */
    private class FakeChapterWebView(override val chapterHref: String) : ChapterWebViewLike {
        override fun evaluateJavascript(script: String, resultCallback: ((String?) -> Unit)?) {
            resultCallback?.invoke(null)
        }
        override var onTap: (() -> Unit)? = null
        override var onRenderGone: (() -> Unit)? = null
        override var onInternalLink: ((String) -> Unit)? = null
        override var onExternalLink: ((String) -> Unit)? = null
        override var annotationsAvailable: Boolean = false
        override var readaloudAvailable: Boolean = false
        override var onSelectionActiveChanged: ((Boolean) -> Unit)? = null
        override var onHighlight: ((String, Double, Rect, String, String) -> Unit)? = null
        override var onAnnotationTap: ((String, Rect) -> Unit)? = null
        override var onAnnotationNoteTap: ((String, Rect) -> Unit)? = null
        override var onFootnoteContent: ((FootnoteContent) -> Unit)? = null
        override var onCrossReferenceTap: ((String) -> Unit)? = null

        fun emitTap() = onTap?.invoke()
        fun emitAnnotationTap(id: String, rect: Rect) = onAnnotationTap?.invoke(id, rect)
        fun emitAnnotationNoteTap(id: String, rect: Rect) = onAnnotationNoteTap?.invoke(id, rect)
        fun emitFootnoteContent(content: FootnoteContent) = onFootnoteContent?.invoke(content)
        fun emitCrossReferenceTap(fragmentId: String) = onCrossReferenceTap?.invoke(fragmentId)
    }

    private class RecordingNav : ContinuousNavigationSink {
        val taps = mutableListOf<Unit>()
        override fun onTap() { taps += Unit }
        override fun onLocator(locator: Locator) = error("not used")
    }

    private class RecordingAnn : ContinuousAnnotationSink {
        val taps = mutableListOf<Pair<String, IntRect>>()
        val notes = mutableListOf<Pair<String, IntRect>>()
        val plays = mutableListOf<String>()
        override fun onAnnotationTap(id: String, rect: IntRect) { taps += id to rect }
        override fun onAnnotationNoteTap(id: String, rect: IntRect) { notes += id to rect }
        override fun onHighlight(locator: Locator, rect: IntRect) = error("not used in this test")
        override fun onPlayFromHere(fragmentRef: String) { plays += fragmentRef }
    }

    private class RecordingLinks : ContinuousLinkSink {
        val footnotes = mutableListOf<FootnoteContent>()
        override fun onFollowInternalLink(link: org.readium.r2.shared.publication.Link, origin: Locator) = Unit
        override fun onExternalLink(url: String) = Unit
        override fun onFootnote(content: FootnoteContent) { footnotes += content }
        override fun captureReturnAnchor(origin: Locator) = Unit
    }

    private fun binderOf(
        nav: ContinuousNavigationSink = RecordingNav(),
        links: ContinuousLinkSink = NoopLinks,
        ann: ContinuousAnnotationSink = RecordingAnn(),
        screenRectOf: (ChapterWebViewLike, Rect) -> Rect = { _, r -> r },
        onCrossReference: (String, String) -> Unit = { _, _ -> },
    ) = ChapterWebViewBinder(
        navigation = nav,
        links = links,
        annotations = ann,
        screenRectOf = screenRectOf,
        onRenderGone = {},
        onInternalLink = {},
        onCrossReference = onCrossReference,
        onSelectionActiveChanged = {},
    )

    @Test
    fun `annotation tap converts rect through provided screenRectOf transform`() {
        val nav = RecordingNav()
        val ann = RecordingAnn()
        val fake = FakeChapterWebView(chapterHref = "chapter-1.xhtml")
        val binder = ChapterWebViewBinder(
            navigation = nav,
            links = NoopLinks,
            annotations = ann,
            screenRectOf = { _, r -> rectOf(r.left + 10, r.top + 20, r.right + 10, r.bottom + 20) },
            onRenderGone = {},
            onInternalLink = {},
            onCrossReference = { _, _ -> },
            onSelectionActiveChanged = {},
        )
        binder.bind(fake, annotationsAvailable = true, readaloudAvailable = true)

        fake.emitAnnotationTap(id = "a1", rect = rectOf(0, 0, 5, 5))

        assertEquals(1, ann.taps.size)
        val (id, rect) = ann.taps.single()
        assertEquals("a1", id)
        assertEquals(IntRect(10, 20, 15, 25), rect)
    }

    @Test
    fun `tap forwards to navigation sink`() {
        val nav = RecordingNav()
        val fake = FakeChapterWebView("chapter-1.xhtml")
        binderOf(nav = nav).bind(fake, annotationsAvailable = false, readaloudAvailable = false)

        fake.emitTap()

        assertTrue(nav.taps.isNotEmpty())
    }

    @Test
    fun `annotation note tap converts rect through provided screenRectOf transform`() {
        val ann = RecordingAnn()
        val fake = FakeChapterWebView("chapter-1.xhtml")
        binderOf(
            ann = ann,
            screenRectOf = { _, r -> rectOf(r.left + 3, r.top + 4, r.right + 3, r.bottom + 4) },
        ).bind(fake, annotationsAvailable = true, readaloudAvailable = false)

        fake.emitAnnotationNoteTap(id = "n1", rect = rectOf(1, 1, 2, 2))

        assertEquals(1, ann.notes.size)
        val (id, rect) = ann.notes.single()
        assertEquals("n1", id)
        assertEquals(IntRect(4, 5, 5, 6), rect)
    }

    @Test
    fun `cross-reference tap forwards fragmentId with the chapter href`() {
        // Regression: same-doc figure/heading anchors used to be dropped in continuous mode — the
        // JS listener only asked whether the target was a footnote and let the WebView's default
        // in-page scroll handle everything else, which broke parent scroll continuity AND skipped
        // the return-to-position card. The binder must now route the tap up as (chapterHref, id).
        val calls = mutableListOf<Pair<String, String>>()
        val fake = FakeChapterWebView(chapterHref = "ch03.xhtml")
        binderOf(
            onCrossReference = { chapterHref, fragmentId -> calls += chapterHref to fragmentId },
        ).bind(fake, annotationsAvailable = false, readaloudAvailable = false)

        fake.emitCrossReferenceTap("c03-fig-0001")

        assertEquals(listOf("ch03.xhtml" to "c03-fig-0001"), calls)
    }

    @Test
    fun `footnote content forwards to link sink`() {
        val links = RecordingLinks()
        val fake = FakeChapterWebView("chapter-1.xhtml")
        binderOf(links = links).bind(fake, annotationsAvailable = false, readaloudAvailable = false)

        val content = FootnoteContent(text = "note")
        fake.emitFootnoteContent(content)

        assertEquals(1, links.footnotes.size)
        assertEquals(content, links.footnotes.single())
    }

    private object NoopLinks : ContinuousLinkSink {
        override fun onFollowInternalLink(link: org.readium.r2.shared.publication.Link, origin: Locator) = Unit
        override fun onExternalLink(url: String) = Unit
        override fun onFootnote(content: FootnoteContent) = Unit
        override fun captureReturnAnchor(origin: Locator) = Unit
    }
}
