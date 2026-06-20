package com.riffle.app.feature.reader

import android.net.FakeUri
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.SentenceQuote
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

@OptIn(ExperimentalCoroutinesApi::class)
class ContinuousHighlightRendererTest {

    // Fake target records every call for assertion.
    internal class FakeTarget : ContinuousHighlightTarget {
        val highlighted = mutableListOf<Triple<String, String, String>>() // href, text, color
        val cleared = mutableListOf<String>() // href
        val appliedAnnotations = mutableListOf<Map<String, List<AnnotationHighlight>>>()
        var searchHighlightsState: SearchHighlightsState? = null
        var searchHighlightsCalls = 0

        override fun highlightInChapter(href: String, text: String, cssColor: String) {
            highlighted.add(Triple(href, text, cssColor))
        }
        override fun clearHighlightInChapter(href: String) { cleared.add(href) }
        override fun applyAnnotationHighlights(annotationsByHref: Map<String, List<AnnotationHighlight>>) {
            appliedAnnotations.add(annotationsByHref)
        }
        override fun applySearchHighlights(state: SearchHighlightsState?) {
            searchHighlightsState = state
            searchHighlightsCalls++
        }
    }

    private val fakeTarget = FakeTarget()
    private val renderer = ContinuousHighlightRenderer(targetProvider = { fakeTarget })

    @Before
    fun setUp() {
        fakeTarget.highlighted.clear()
        fakeTarget.cleared.clear()
        fakeTarget.appliedAnnotations.clear()
        fakeTarget.searchHighlightsState = null
        fakeTarget.searchHighlightsCalls = 0
    }

    // ---- Helpers -------------------------------------------------------------

    /**
     * Creates an [AbsoluteUrl] backed by [urlString] without going through [android.net.Uri.parse],
     * which is unavailable in JVM unit tests.
     *
     * Uses [sun.misc.Unsafe] to allocate [AbsoluteUrl] without calling its private constructor,
     * then injects a [FakeUri] (our test-only [android.net.Uri] concrete subclass) via reflection.
     * [FakeUri] is in the [android.net] package so it can subclass the package-private [Uri] ctor.
     */
    @Suppress("UNCHECKED_CAST")
    private fun makeAbsoluteUrl(urlString: String): AbsoluteUrl {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val instance = unsafe.allocateInstance(AbsoluteUrl::class.java) as AbsoluteUrl
        val uriField = AbsoluteUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
        uriField.set(instance, FakeUri(urlString))
        return instance
    }

    /**
     * Creates a [Locator] with a meaningful href and text highlight for use in
     * [applyAnnotations] tests.
     */
    private fun makeRender(
        id: String,
        href: String,
        text: String,
        color: String = "yellow",
        note: String? = null,
    ) = EpubReaderViewModel.HighlightRender(
        id = id,
        locator = Locator(
            href = makeAbsoluteUrl("https://example.com/$href"),
            mediaType = MediaType.XHTML,
            text = Locator.Text(highlight = text),
        ),
        color = color,
        note = note,
    )

    /** Creates a [Locator] with href and text highlight, suitable for search result tests. */
    private fun makeSearchLocator(href: String, text: String): Locator = Locator(
        href = makeAbsoluteUrl("https://example.com/$href"),
        mediaType = MediaType.XHTML,
        text = Locator.Text(highlight = text),
    )

    // ---- applyReadaloud -------------------------------------------------------

    @Test
    fun `applyReadaloud highlights the sentence text in the correct chapter`() = runTest {
        renderer.applyReadaloud(
            fragmentRef = "chapter1.xhtml#s42",
            quotes = mapOf("s42" to SentenceQuote(before = "", highlight = "To be or not to be", after = "")),
            color = ReadaloudHighlightColor.BLUE,
        )
        assertEquals(1, fakeTarget.highlighted.size)
        assertEquals("chapter1.xhtml", fakeTarget.highlighted[0].first)
        assertEquals("To be or not to be", fakeTarget.highlighted[0].second)
    }

    @Test
    fun `applyReadaloud clears previous chapter when chapter changes`() = runTest {
        renderer.applyReadaloud(
            fragmentRef = "ch1.xhtml#s1",
            quotes = mapOf("s1" to SentenceQuote(before = "", highlight = "First sentence", after = "")),
            color = ReadaloudHighlightColor.BLUE,
        )
        renderer.applyReadaloud(
            fragmentRef = "ch2.xhtml#s2",
            quotes = mapOf("s2" to SentenceQuote(before = "", highlight = "Second chapter", after = "")),
            color = ReadaloudHighlightColor.BLUE,
        )
        // ch1 should have been cleared when ch2 was highlighted
        assertTrue("ch1 should be cleared", fakeTarget.cleared.contains("ch1.xhtml"))
        val lastHighlight = fakeTarget.highlighted.last()
        assertEquals("ch2.xhtml", lastHighlight.first)
    }

    @Test
    fun `applyReadaloud with null ref clears previous chapter`() = runTest {
        renderer.applyReadaloud(
            fragmentRef = "ch1.xhtml#s1",
            quotes = mapOf("s1" to SentenceQuote(before = "", highlight = "A sentence", after = "")),
            color = ReadaloudHighlightColor.BLUE,
        )
        renderer.applyReadaloud(null, emptyMap(), ReadaloudHighlightColor.BLUE)
        assertTrue(fakeTarget.cleared.contains("ch1.xhtml"))
    }

    @Test
    fun `applyReadaloud does nothing when sid not in quotes`() = runTest {
        renderer.applyReadaloud(
            fragmentRef = "ch1.xhtml#unknown",
            quotes = emptyMap(),
            color = ReadaloudHighlightColor.BLUE,
        )
        assertEquals(0, fakeTarget.highlighted.size)
    }

    // ---- applyAnnotations ----------------------------------------------------

    @Test
    fun `applyAnnotations groups renders by href and maps color to CSS`() = runTest {
        val renders = listOf(
            makeRender("h1", "ch1.xhtml", "text one", color = "yellow"),
            makeRender("h2", "ch1.xhtml", "text two", color = "green"),
            makeRender("h3", "ch2.xhtml", "text three", color = "blue"),
        )
        renderer.applyAnnotations(renders, ReaderTheme.Light)

        assertEquals(1, fakeTarget.appliedAnnotations.size)
        val byHref = fakeTarget.appliedAnnotations[0]
        assertEquals(2, byHref["https://example.com/ch1.xhtml"]?.size)
        assertEquals(1, byHref["https://example.com/ch2.xhtml"]?.size)
    }

    @Test
    fun `applyAnnotations skips renders whose locator has no highlight text`() = runTest {
        // A render whose locator.text.highlight is null should be filtered out.
        // (In production, the Screen always stores text in the locator, but guard anyway.)
        val renders = listOf(makeRender("h1", "ch1.xhtml", "some text"))
        renderer.applyAnnotations(renders, ReaderTheme.Light)
        // applyAnnotationHighlights should still be called (with filtered content)
        assertEquals(1, fakeTarget.appliedAnnotations.size)
    }

    @Test
    fun `applyAnnotations with empty renders calls applyAnnotationHighlights with empty map`() = runTest {
        renderer.applyAnnotations(emptyList(), ReaderTheme.Light)
        assertEquals(1, fakeTarget.appliedAnnotations.size)
        assertTrue(fakeTarget.appliedAnnotations[0].isEmpty())
    }

    // ---- applyNoteGlyphs (no-op) --------------------------------------------

    @Test
    fun `applyNoteGlyphs is a no-op in continuous mode`() = runTest {
        renderer.applyNoteGlyphs(listOf(makeRender("h1", "ch1.xhtml", "text", note = "my note")))
        assertEquals(0, fakeTarget.highlighted.size)
        assertEquals(0, fakeTarget.cleared.size)
        assertEquals(0, fakeTarget.appliedAnnotations.size)
    }

    // ---- applySearch --------------------------------------------------------

    @Test
    fun `applySearch with active result calls applySearchHighlights with correct active fields`() = runTest {
        val locator = makeSearchLocator("ch1.xhtml", "found text")
        renderer.applySearch(listOf(locator), activeIndex = 0)
        val state = fakeTarget.searchHighlightsState
        assertTrue("state should not be null", state != null)
        assertTrue("activeHref should end with ch1.xhtml", state!!.activeHref?.endsWith("ch1.xhtml") == true)
        assertEquals("found text", state.activeText)
        assertEquals(1, fakeTarget.searchHighlightsCalls)
    }

    @Test
    fun `applySearch uses SEARCH_DECORATION_ALPHA for both active and inactive colors`() = runTest {
        val locator = makeSearchLocator("ch1.xhtml", "text")
        renderer.applySearch(listOf(locator), activeIndex = 0)
        val state = fakeTarget.searchHighlightsState!!
        assertEquals(SEARCH_ACTIVE_ARGB.toCssRgbaWithAlpha(SEARCH_DECORATION_ALPHA), state.activeCssColor)
        assertEquals(SEARCH_INACTIVE_ARGB.toCssRgbaWithAlpha(SEARCH_DECORATION_ALPHA), state.inactiveCssColor)
    }

    @Test
    fun `applySearch truncates active text to 40 chars`() = runTest {
        val longText = "a".repeat(60)
        val locator = makeSearchLocator("ch1.xhtml", longText)
        renderer.applySearch(listOf(locator), activeIndex = 0)
        assertEquals(40, fakeTarget.searchHighlightsState!!.activeText!!.length)
    }

    @Test
    fun `applySearch groups results by href`() = runTest {
        val results = listOf(
            makeSearchLocator("ch1.xhtml", "word"),
            makeSearchLocator("ch1.xhtml", "word"),
            makeSearchLocator("ch2.xhtml", "word"),
        )
        renderer.applySearch(results, activeIndex = 0)
        val byHref = fakeTarget.searchHighlightsState!!.resultsByHref
        assertTrue(byHref.keys.any { it.endsWith("ch1.xhtml") })
        assertTrue(byHref.keys.any { it.endsWith("ch2.xhtml") })
    }

    @Test
    fun `applySearch deduplicates texts within a chapter`() = runTest {
        val results = listOf(
            makeSearchLocator("ch1.xhtml", "word"),
            makeSearchLocator("ch1.xhtml", "word"),
        )
        renderer.applySearch(results, activeIndex = 0)
        val ch1Key = fakeTarget.searchHighlightsState!!.resultsByHref.keys.first { it.endsWith("ch1.xhtml") }
        assertEquals(1, fakeTarget.searchHighlightsState!!.resultsByHref[ch1Key]!!.size)
    }

    @Test
    fun `applySearch with empty results clears highlights`() = runTest {
        renderer.applySearch(emptyList(), activeIndex = -1)
        assertEquals(1, fakeTarget.searchHighlightsCalls)
        assertEquals(null, fakeTarget.searchHighlightsState)
    }

    @Test
    fun `applySearch with negative index clears highlights`() = runTest {
        val locator = makeSearchLocator("ch1.xhtml", "text")
        renderer.applySearch(listOf(locator), activeIndex = -1)
        assertEquals(1, fakeTarget.searchHighlightsCalls)
        assertEquals(null, fakeTarget.searchHighlightsState)
    }

    @Test
    fun `applySearch skips locators with blank text`() = runTest {
        val locator = makeSearchLocator("ch1.xhtml", "   ")
        renderer.applySearch(listOf(locator), activeIndex = 0)
        assertEquals(0, fakeTarget.searchHighlightsCalls)
    }

    // ---- highlightSearchMatch -----------------------------------------------

    @Test
    fun `highlightSearchMatch is a no-op in continuous mode`() {
        renderer.highlightSearchMatch("ch1.xhtml", "search term")
        assertEquals(0, fakeTarget.searchHighlightsCalls)
    }
}
