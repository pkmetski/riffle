@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.core.domain.SentenceQuote
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator

@OptIn(ExperimentalCoroutinesApi::class)
class ReadiumHighlightRendererTest {

    private val applied = mutableListOf<Pair<List<Decoration>, String>>()

    private val renderer = ReadiumHighlightRenderer(
        applyDecorationsBlock = { decorations, group -> applied.add(decorations to group) },
        fragmentLocator = { ref, _ ->
            // Return a minimal locator for any non-blank ref
            if (ref.isNotBlank()) minimalLocator(ref.substringBefore('#')) else null
        },
    )

    @Before
    fun setUp() {
        applied.clear()
    }

    private fun minimalLocator(@Suppress("UNUSED_PARAMETER") href: String): Locator {
        // Locator(Url, MediaType, ...) requires android.net.Uri which is not available in JVM
        // unit tests. Allocate a Locator stub via Unsafe — the resulting instance has all fields
        // null/zero but is a real Locator that can be stored in HighlightRender and Decoration.
        // Our assertions only care about decoration.id and group names, not the locator content.
        @Suppress("UNCHECKED_CAST")
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Locator::class.java) as Locator
    }

    private fun makeRender(
        id: String,
        href: String,
        color: String = "yellow",
        note: String? = null,
    ) = EpubReaderViewModel.HighlightRender(
        id = id,
        locator = minimalLocator(href),
        color = color,
        note = note,
    )

    // ---- applySentenceHighlight -------------------------------------------------------

    @Test
    fun `applySentenceHighlight with non-null ref applies sentence highlight group`() = runTest {
        renderer.applySentenceHighlight(
            fragmentRef = "chapter1.xhtml#s1",
            quotes = mapOf("s1" to SentenceQuote(before = "", highlight = "Hello world", after = "")),
            color = HighlightColor.BLUE,
        )
        val sentenceCalls = applied.filter { it.second == "readaloud" }
        assertEquals(2, sentenceCalls.size) // applyDecorationsWithClear: clear then apply
        assertEquals(emptyList<Decoration>(), sentenceCalls[0].first)
        assertEquals(1, sentenceCalls[1].first.size)
        assertEquals("readaloud_active", sentenceCalls[1].first[0].id)
    }

    @Test
    fun `applySentenceHighlight with null ref clears group and does not re-clear if already clear`() = runTest {
        // First call: nothing to clear → no dispatch
        renderer.applySentenceHighlight(null, emptyMap(), HighlightColor.BLUE)
        assertEquals(0, applied.size)

        // Apply one, then clear
        renderer.applySentenceHighlight("c.xhtml#s1", emptyMap(), HighlightColor.BLUE)
        applied.clear()
        renderer.applySentenceHighlight(null, emptyMap(), HighlightColor.BLUE)
        assertEquals(1, applied.size)
        assertEquals(emptyList<Decoration>(), applied[0].first)
        assertEquals("readaloud", applied[0].second)
    }

    // ---- applyAnnotations ----------------------------------------------------

    @Test
    fun `applyAnnotations with renders produces one decoration per render`() = runTest {
        val renders = listOf(
            makeRender("h1", "c1.xhtml", color = "yellow"),
            makeRender("h2", "c1.xhtml", color = "green"),
        )
        renderer.applyAnnotations(renders)
        val annotationCalls = applied.filter { it.second == "annotations" }
        // A single clear followed by a single apply. No settle loop — that lived until we
        // discovered it was pinning the WebView main thread for 2.6s on cold open and causing
        // 2-3s of input sluggishness. Post-apply layout shifts are covered by the outer
        // LaunchedEffect re-keying on pageLoadGeneration / reflowGeneration.
        assertEquals("expected exactly clear + apply, no settle re-applies", 2, annotationCalls.size)
        assertEquals(emptyList<Decoration>(), annotationCalls[0].first)
        assertEquals(2, annotationCalls[1].first.size)
        assertEquals("h1", annotationCalls[1].first[0].id)
        assertEquals("h2", annotationCalls[1].first[1].id)
    }

    @Test
    fun `applyAnnotations expands decoration boxes after Readium applies them`() = runTest {
        val callOrder = mutableListOf<String>()
        val rendererWithGeometryAdjustment = ReadiumHighlightRenderer(
            applyDecorationsBlock = { _, group -> callOrder.add("decorate:$group") },
            fragmentLocator = { ref, _ ->
                if (ref.isNotBlank()) minimalLocator(ref.substringBefore('#')) else null
            },
            evaluateJavascript = { script ->
                if (script.contains("__riffleFillReadiumHighlightLeading")) {
                    callOrder.add("fillLineLeading")
                }
            },
        )

        rendererWithGeometryAdjustment.applyAnnotations(listOf(makeRender("h1", "c1.xhtml")))

        val lastDecoration = callOrder.indexOfLast { it == "decorate:annotations" }
        val lineLeading = callOrder.indexOf("fillLineLeading")
        assertTrue("decoration apply must occur", lastDecoration >= 0)
        assertTrue(
            "line-leading adjustment must run after Readium creates its boxes; order=$callOrder",
            lineLeading > lastDecoration,
        )
    }

    // Regression: bold/italic DOM injection must be queued BEFORE Readium measures decoration
    // positions. EmphasisDomInjector wraps bold/italic text in <span> elements causing text
    // reflow; if injected after applyDecorationsWithClear, Readium's baked tap-target rects
    // become stale and the annotation is not tappable until close/reopen.
    // This test fails if someone reverts the injectEmphasisDom() call to run after the
    // applyDecorationsWithClear call (the annotation-not-tappable bug).
    @Test
    fun `applyAnnotations with bold emphasis queues DOM injection before decoration apply`() = runTest {
        val callOrder = mutableListOf<String>()
        val rendererWithDomInjector = ReadiumHighlightRenderer(
            applyDecorationsBlock = { _, group -> callOrder.add("decorate:$group") },
            fragmentLocator = { ref, _ ->
                if (ref.isNotBlank()) minimalLocator(ref.substringBefore('#')) else null
            },
            evaluateJavascript = { callOrder.add("domInject") },
            emphasisRangeProvider = {
                listOf(
                    EmphasisDomInjector.EmphasisRange(
                        id = "h1",
                        textSnippet = "bold text",
                        textBefore = "",
                        styles = setOf(EmphasisStyle.BOLD),
                    )
                )
            },
        )
        val render = EpubReaderViewModel.HighlightRender(
            id = "h1",
            locator = minimalLocator("c.xhtml"),
            color = "yellow",
            note = null,
            emphasisStyles = setOf(EmphasisStyle.BOLD),
        )

        rendererWithDomInjector.applyAnnotations(listOf(render))

        val domInjectIndex = callOrder.indexOf("domInject")
        val firstDecorateAnnotationsIndex = callOrder.indexOfFirst { it == "decorate:annotations" }
        assertTrue("domInject index should exist", domInjectIndex >= 0)
        assertTrue("decorate:annotations index should exist", firstDecorateAnnotationsIndex >= 0)
        assertTrue(
            "DOM injection must be queued before decoration apply (got order: $callOrder)",
            domInjectIndex < firstDecorateAnnotationsIndex,
        )
    }

    // Regression: the settle loop MUST stay out. Its return would re-introduce the cold-open
    // sluggishness. If someone re-adds a loop here, this test will flip red.
    @Test
    fun `applyAnnotations does not spin a settle loop after the initial apply`() = runTest {
        renderer.applyAnnotations(listOf(makeRender("h1", "c1.xhtml")))
        val annotationCalls = applied.filter { it.second == "annotations" }
        assertEquals(2, annotationCalls.size)
    }

    @Test
    fun `applyAnnotations with empty list clears group`() = runTest {
        // Apply something first so hasAnnotationDecorations = true
        renderer.applyAnnotations(listOf(makeRender("h1", "c1.xhtml")))
        applied.clear()

        renderer.applyAnnotations(emptyList())
        assertEquals(1, applied.size)
        assertEquals(emptyList<Decoration>(), applied[0].first)
        assertEquals("annotations", applied[0].second)
    }

    @Test
    fun `applyAnnotations empty list is no-op when no decorations active`() = runTest {
        renderer.applyAnnotations(emptyList())
        assertEquals(0, applied.size)
    }

    // ---- applyNoteGlyphs ----------------------------------------------------

    @Test
    fun `applyNoteGlyphs only decorates renders that have a note`() = runTest {
        val renders = listOf(
            makeRender("h1", "c.xhtml", note = "My note"),
            makeRender("h2", "c.xhtml", note = null),
        )
        renderer.applyNoteGlyphs(renders)
        val decorationCall = applied.last()
        assertEquals("annotation-notes", decorationCall.second)
        assertEquals(1, decorationCall.first.size)
        assertEquals("h1", decorationCall.first[0].id)
    }

    @Test
    fun `applyNoteGlyphs clears group when no noted renders`() = runTest {
        // Apply one first
        renderer.applyNoteGlyphs(listOf(makeRender("h1", "c.xhtml", note = "note")))
        applied.clear()

        renderer.applyNoteGlyphs(listOf(makeRender("h2", "c.xhtml", note = null)))
        assertEquals(1, applied.size)
        assertEquals(emptyList<Decoration>(), applied[0].first)
        assertEquals("annotation-notes", applied[0].second)
    }

    // ---- applySearch ---------------------------------------------------------

    @Test
    fun `applySearch with results applies search group`() = runTest {
        val results = listOf(minimalLocator("c.xhtml"), minimalLocator("c.xhtml"))
        renderer.applySearch(results, activeIndex = 0)
        val searchCalls = applied.filter { it.second == "search" }
        // A single apply. No settle loop — see `applyAnnotations does not spin a settle loop`.
        assertEquals(1, searchCalls.size)
        assertEquals(2, searchCalls[0].first.size)
    }

    @Test
    fun `applySearch with empty results clears group`() = runTest {
        // Apply first so sentinel = true
        renderer.applySearch(listOf(minimalLocator("c.xhtml")), 0)
        applied.clear()

        renderer.applySearch(emptyList(), 0)
        val clearCall = applied.firstOrNull { it.second == "search" }
        assertEquals(emptyList<Decoration>(), clearCall?.first)
    }

    // ---- highlightSearchMatch ------------------------------------------------

    @Test
    fun `highlightSearchMatch is a no-op`() {
        renderer.highlightSearchMatch("c.xhtml", "some text")
        assertEquals(0, applied.size)
    }
}
