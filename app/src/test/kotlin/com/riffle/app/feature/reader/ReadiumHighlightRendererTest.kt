@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.feature.reader.HighlightRender
import com.riffle.feature.reader.NOTE_GLYPH_DECORATION_GROUP
import com.riffle.feature.reader.NOTE_GLYPH_FOCUS_ID_JS_KEY
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
import com.riffle.app.feature.reader.HighlightTintStyle
import com.riffle.feature.reader.EmphasisDomInjector

@OptIn(ExperimentalCoroutinesApi::class)
class ReadiumHighlightRendererTest {

    private val applied = mutableListOf<Pair<List<Decoration>, String>>()

    private val renderer = ReadiumHighlightRenderer(
        applyDecorationsBlock = { decorations, group -> applied.add(decorations to group) },
        fragmentLocator = { ref, _ ->
            // Return a minimal locator for any non-blank ref
            if (ref.isNotBlank()) minimalLocator(ref.substringBefore('#')) else null
        },
        locatorFromJson = { _ -> minimalLocator("fake") },
    )

    @Before
    fun setUp() {
        applied.clear()
    }

    private fun minimalLocator(@Suppress("UNUSED_PARAMETER") href: String): Locator {
        // Used for applySearch tests which take List<Locator> directly.
        // Allocate a Locator stub via Unsafe — all fields null/zero but a real Locator instance.
        @Suppress("UNCHECKED_CAST")
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Locator::class.java) as Locator
    }

    private fun makeLocatorJson(href: String): String =
        """{"href":"$href","type":"application/xhtml+xml"}"""

    private fun makeRender(
        id: String,
        href: String,
        color: String = "yellow",
        note: String? = null,
    ) = HighlightRender(
        id = id,
        locatorJson = makeLocatorJson(href),
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

    // Regression: after changing the readaloud highlight colour in Settings (while the reader may
    // be in the back stack), the LaunchedEffect re-fires with the new colour and the decoration
    // must be re-applied. This test fails if applySentenceHighlight returns early on a same-ref
    // call instead of re-issuing applyDecorationsWithClear with the new colour.
    @Test
    fun `applySentenceHighlight re-applies with new color when called again with same ref`() = runTest {
        val quote = mapOf("s1" to SentenceQuote(before = "", highlight = "Hello", after = ""))
        renderer.applySentenceHighlight("c.xhtml#s1", quote, HighlightColor.BLUE)
        applied.clear()

        renderer.applySentenceHighlight("c.xhtml#s1", quote, HighlightColor.YELLOW)

        val sentenceCalls = applied.filter { it.second == "readaloud" }
        assertEquals("color change must re-issue clear + apply", 2, sentenceCalls.size)
        assertEquals(emptyList<Decoration>(), sentenceCalls[0].first)
        assertEquals(1, sentenceCalls[1].first.size)
        val style = sentenceCalls[1].first[0].style as HighlightTintStyle
        assertEquals(HighlightColor.YELLOW.argb, style.tint)
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
        // First apply: renders changed (null → list) so Readium diffs directly without clearing.
        // No settle loop — that lived until we discovered it was pinning the WebView main thread
        // for 2.6s on cold open. Post-apply layout shifts are covered by the outer LaunchedEffect
        // re-keying on pageLoadGeneration / reflowGeneration (which trigger clear+apply).
        assertEquals("expected a single direct apply on first call, no clear", 1, annotationCalls.size)
        assertEquals(2, annotationCalls[0].first.size)
        assertEquals("h1", annotationCalls[0].first[0].id)
        assertEquals("h2", annotationCalls[0].first[1].id)
    }

    @Test
    fun `applyAnnotations forceRefresh produces one direct apply call without clear`() = runTest {
        val renders = listOf(makeRender("h1", "c1.xhtml", color = "yellow"))
        renderer.applyAnnotations(renders)
        applied.clear()

        // forceRefresh=true — layout changed (page loaded or reflowed). Readium's diff is a no-op
        // when the decoration list is identical (same id/locator/style). When Readium's internal
        // requestLayout fires on column layout changes, it removes and re-creates all elements in
        // a single rAF — no additional clear+apply is needed here to cause repositioning.
        renderer.applyAnnotations(renders)
        val annotationCalls = applied.filter { it.second == "annotations" }
        assertEquals("forceRefresh must produce exactly one apply call (not clear+apply)", 1, annotationCalls.size)
        assertEquals(1, annotationCalls[0].first.size)
    }

    // Regression: sync-on-open re-emits the same annotation list from DB (no layout change).
    // forceRefresh=false must produce a single apply with unmodified locators so Readium's diff
    // is a no-op — no unnecessary repositioning, no flash.
    @Test
    fun `applyAnnotations same renders without layout change produces one direct apply`() = runTest {
        val renders = listOf(makeRender("h1", "c1.xhtml", color = "yellow"))
        renderer.applyAnnotations(renders)
        applied.clear()

        renderer.applyAnnotations(renders)
        val annotationCalls = applied.filter { it.second == "annotations" }
        assertEquals("same renders with no layout change must produce one apply call", 1, annotationCalls.size)
    }

    @Test
    fun `applyAnnotations changed renders on second call uses direct apply to avoid flash`() = runTest {
        val renders1 = listOf(makeRender("h1", "c1.xhtml", color = "yellow"))
        renderer.applyAnnotations(renders1)
        applied.clear()

        // Different renders with forceRefresh=false (data update: annotation added/recoloured) —
        // Readium diffs and repositions the changed decorations in a single JS call without an
        // intermediate "all cleared" frame, so the user sees no flash.
        val renders2 = listOf(
            makeRender("h1", "c1.xhtml", color = "green"),
            makeRender("h2", "c1.xhtml", color = "blue"),
        )
        renderer.applyAnnotations(renders2)
        val annotationCalls = applied.filter { it.second == "annotations" }
        assertEquals("data-only change must use direct apply (no clear flash)", 1, annotationCalls.size)
        assertEquals(2, annotationCalls[0].first.size)
    }

    // The MutationObserver that expands decoration boxes to full line-height must be installed
    // BEFORE Readium's applyDecorations call so it is already watching when Readium's JS adds
    // the decoration elements to the DOM. The expansion then fires as a microtask (before any
    // paint), not in a later requestAnimationFrame — this is the change that eliminates the
    // brief "thin highlight" frame that users saw as a flash.
    @Test
    fun `applyAnnotations installs line-leading observer before decoration apply`() = runTest {
        val callOrder = mutableListOf<String>()
        val rendererWithObserver = ReadiumHighlightRenderer(
            applyDecorationsBlock = { _, group -> callOrder.add("decorate:$group") },
            fragmentLocator = { ref, _ ->
                if (ref.isNotBlank()) minimalLocator(ref.substringBefore('#')) else null
            },
            evaluateJavascript = { script ->
                if (script.contains("__riffleLeadingObserver")) {
                    callOrder.add("installObserver")
                }
            },
            locatorFromJson = { _ -> minimalLocator("fake") },
        )

        rendererWithObserver.applyAnnotations(listOf(makeRender("h1", "c1.xhtml")))

        val observerInstall = callOrder.indexOf("installObserver")
        val firstDecoration = callOrder.indexOf("decorate:annotations")
        assertTrue("observer installation must occur", observerInstall >= 0)
        assertTrue("decoration apply must occur", firstDecoration >= 0)
        assertTrue(
            "observer must be installed before decoration apply so it watches when Readium adds elements; order=$callOrder",
            observerInstall < firstDecoration,
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
            evaluateJavascript = { script ->
                // Discriminate: observer install vs. emphasis DOM injection vs. other JS.
                when {
                    script.contains("__riffleLeadingObserver") -> callOrder.add("installObserver")
                    script.contains("data-riffle-em") -> callOrder.add("emphasisDomInject")
                    else -> callOrder.add("otherJs")
                }
            },
            locatorFromJson = { _ -> minimalLocator("fake") },
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
        val render = HighlightRender(
            id = "h1",
            locatorJson = makeLocatorJson("c.xhtml"),
            color = "yellow",
            note = null,
            emphasisStyles = setOf(EmphasisStyle.BOLD),
        )

        rendererWithDomInjector.applyAnnotations(listOf(render))

        val emphasisInjectIndex = callOrder.indexOf("emphasisDomInject")
        val firstDecorateAnnotationsIndex = callOrder.indexOfFirst { it == "decorate:annotations" }
        assertTrue("observer installation must occur", callOrder.contains("installObserver"))
        assertTrue("emphasis DOM injection must occur; order=$callOrder", emphasisInjectIndex >= 0)
        assertTrue("decorate:annotations must occur; order=$callOrder", firstDecorateAnnotationsIndex >= 0)
        assertTrue(
            "emphasis DOM injection must be queued before decoration apply (got order: $callOrder)",
            emphasisInjectIndex < firstDecorateAnnotationsIndex,
        )
    }

    // Regression: the settle loop MUST stay out. Its return would re-introduce the cold-open
    // sluggishness. If someone re-adds a loop here, this test will flip red.
    @Test
    fun `applyAnnotations does not spin a settle loop after the initial apply`() = runTest {
        renderer.applyAnnotations(listOf(makeRender("h1", "c1.xhtml")))
        val annotationCalls = applied.filter { it.second == "annotations" }
        // First apply = 1 direct call (no clear). A settle loop would push this above 1.
        assertEquals(1, annotationCalls.size)
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
        assertEquals(NOTE_GLYPH_DECORATION_GROUP, decorationCall.second)
        assertEquals(1, decorationCall.first.size)
        assertEquals("h1", decorationCall.first[0].id)
    }

    @Test
    fun `applyNoteGlyphs completes a pending focus after Readium adds the range`() = runTest {
        val callOrder = mutableListOf<String>()
        val rendererWithJavascript = ReadiumHighlightRenderer(
            applyDecorationsBlock = { _, group -> callOrder += "decorate:$group" },
            fragmentLocator = { ref, _ ->
                if (ref.isNotBlank()) minimalLocator(ref.substringBefore('#')) else null
            },
            evaluateJavascript = { script -> callOrder += "javascript:$script" },
            locatorFromJson = { _ -> minimalLocator("fake") },
        )

        rendererWithJavascript.applyNoteGlyphs(
            listOf(makeRender("h1", "c.xhtml", note = "My note")),
        )

        val finalDecoration = callOrder.indexOfLast { it == "decorate:$NOTE_GLYPH_DECORATION_GROUP" }
        val viewportClamp = callOrder.indexOfFirst {
            it.startsWith("javascript:") &&
                it.contains(".$NOTE_GLYPH_ICON_CLASS") &&
                it.contains("spreadLeft + $NOTE_GLYPH_VIEWPORT_INSET_PX")
        }
        val focusCompletion = callOrder.indexOfFirst {
                it.startsWith("javascript:") &&
                it.contains("window.$NOTE_GLYPH_FOCUS_ID_JS_KEY") &&
                it.contains("item.range.getClientRects()") &&
                it.contains("se.scrollLeft = target") &&
                it.contains("matchedFrames >= 60") &&
                it.contains("frames++ < 600")
        }
        assertTrue("note group is applied", finalDecoration >= 0)
        assertTrue(
            "viewport clamp is queued after the note DOM is applied: $callOrder",
            viewportClamp > finalDecoration,
        )
        assertTrue(
            "focus completion is queued after the note Range is applied: $callOrder",
            focusCompletion > viewportClamp,
        )
    }

    @Test
    fun `applyNoteGlyphs forceRefresh produces one direct apply call without clear`() = runTest {
        val renders = listOf(makeRender("h1", "c.xhtml", note = "My note"))
        renderer.applyNoteGlyphs(renders)
        applied.clear()

        // forceRefresh=true: Readium's requestLayout handles layout repositioning automatically;
        // no generation marker or clear+apply is needed here.
        renderer.applyNoteGlyphs(renders)
        val glyphCalls = applied.filter { it.second == NOTE_GLYPH_DECORATION_GROUP }
        assertEquals("forceRefresh must produce one apply call for glyphs", 1, glyphCalls.size)
        assertEquals(1, glyphCalls[0].first.size)
    }

    @Test
    fun `applyNoteGlyphs same noted renders without layout change uses direct apply`() = runTest {
        val renders = listOf(makeRender("h1", "c.xhtml", note = "My note"))
        renderer.applyNoteGlyphs(renders)
        applied.clear()

        renderer.applyNoteGlyphs(renders)
        val glyphCalls = applied.filter { it.second == NOTE_GLYPH_DECORATION_GROUP }
        assertEquals("same noted renders with no layout change must use direct apply", 1, glyphCalls.size)
    }

    @Test
    fun `applyNoteGlyphs changed noted renders on second call uses direct apply`() = runTest {
        val renders1 = listOf(makeRender("h1", "c.xhtml", note = "note"))
        renderer.applyNoteGlyphs(renders1)
        applied.clear()

        val renders2 = listOf(
            makeRender("h1", "c.xhtml", note = "note"),
            makeRender("h2", "c.xhtml", note = "another note"),
        )
        renderer.applyNoteGlyphs(renders2)
        val glyphCalls = applied.filter { it.second == NOTE_GLYPH_DECORATION_GROUP }
        assertEquals("changed noted renders must use direct apply (no clear flash)", 1, glyphCalls.size)
        assertEquals(2, glyphCalls[0].first.size)
    }

    @Test
    fun `applyNoteGlyphs clears group when no noted renders`() = runTest {
        // Apply one first
        renderer.applyNoteGlyphs(listOf(makeRender("h1", "c.xhtml", note = "note")))
        applied.clear()

        renderer.applyNoteGlyphs(listOf(makeRender("h2", "c.xhtml", note = null)))
        assertEquals(1, applied.size)
        assertEquals(emptyList<Decoration>(), applied[0].first)
        assertEquals(NOTE_GLYPH_DECORATION_GROUP, applied[0].second)
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
