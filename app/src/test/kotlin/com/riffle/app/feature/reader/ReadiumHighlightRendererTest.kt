@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReaderTheme
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
    private var navigatorStamp: Any? = Any()

    private val renderer = ReadiumHighlightRenderer(
        applyDecorationsBlock = { decorations, group -> applied.add(decorations to group) },
        fragmentLocator = { ref, _ ->
            // Return a minimal locator for any non-blank ref
            if (ref.isNotBlank()) minimalLocator(ref.substringBefore('#')) else null
        },
        currentNavigatorStamp = { navigatorStamp },
    )

    @Before
    fun setUp() {
        applied.clear()
        navigatorStamp = Any()
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

    // ---- applyReadaloud -------------------------------------------------------

    @Test
    fun `applyReadaloud with non-null ref applies readaloud group`() = runTest {
        renderer.applyReadaloud(
            fragmentRef = "chapter1.xhtml#s1",
            quotes = mapOf("s1" to SentenceQuote(before = "", highlight = "Hello world", after = "")),
            color = ReadaloudHighlightColor.BLUE,
        )
        val readaloudCalls = applied.filter { it.second == "readaloud" }
        assertEquals(2, readaloudCalls.size) // applyDecorationsWithClear: clear then apply
        assertEquals(emptyList<Decoration>(), readaloudCalls[0].first)
        assertEquals(1, readaloudCalls[1].first.size)
        assertEquals("readaloud_active", readaloudCalls[1].first[0].id)
    }

    @Test
    fun `applyReadaloud with null ref clears group and does not re-clear if already clear`() = runTest {
        // First call: nothing to clear → no dispatch
        renderer.applyReadaloud(null, emptyMap(), ReadaloudHighlightColor.BLUE)
        assertEquals(0, applied.size)

        // Apply one, then clear
        renderer.applyReadaloud("c.xhtml#s1", emptyMap(), ReadaloudHighlightColor.BLUE)
        applied.clear()
        renderer.applyReadaloud(null, emptyMap(), ReadaloudHighlightColor.BLUE)
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
        renderer.applyAnnotations(renders, ReaderTheme.Light)
        // applyDecorationsWithClear = clear + apply → 2 calls to the block
        val decorationCall = applied.last()
        assertEquals("annotations", decorationCall.second)
        assertEquals(2, decorationCall.first.size)
        assertEquals("h1", decorationCall.first[0].id)
        assertEquals("h2", decorationCall.first[1].id)
    }

    @Test
    fun `applyAnnotations with empty list clears group`() = runTest {
        // Apply something first so hasAnnotationDecorations = true
        renderer.applyAnnotations(listOf(makeRender("h1", "c1.xhtml")), ReaderTheme.Light)
        applied.clear()

        renderer.applyAnnotations(emptyList(), ReaderTheme.Light)
        assertEquals(1, applied.size)
        assertEquals(emptyList<Decoration>(), applied[0].first)
        assertEquals("annotations", applied[0].second)
    }

    @Test
    fun `applyAnnotations empty list is no-op when no decorations active`() = runTest {
        renderer.applyAnnotations(emptyList(), ReaderTheme.Light)
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
        // First call is the immediate apply; settle loop adds more (but UnconfinedTestDispatcher
        // will advance through delays).
        val searchCalls = applied.filter { it.second == "search" }
        assertTrue(searchCalls.isNotEmpty())
        assertEquals(2, searchCalls.first().first.size)
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

    @Test
    fun `applySearch settle loop aborts when navigator stamp changes`() = runTest {
        val recorder = mutableListOf<Pair<List<Decoration>, String>>()
        val abortingRenderer = ReadiumHighlightRenderer(
            applyDecorationsBlock = { decorations, group -> recorder.add(decorations to group) },
            fragmentLocator = { ref, _ -> if (ref.isNotBlank()) minimalLocator(ref.substringBefore('#')) else null },
            currentNavigatorStamp = { Any() }, // new object every call → stamp always changes
        )
        abortingRenderer.applySearch(listOf(minimalLocator("c.xhtml")), activeIndex = 0)
        val searchCalls = recorder.filter { it.second == "search" }
        // Only the initial apply fires; settle loop aborts on first resume because stamp changed
        assertEquals(1, searchCalls.size)
        assertEquals(1, searchCalls[0].first.size)
    }

    // ---- highlightSearchMatch ------------------------------------------------

    @Test
    fun `highlightSearchMatch is a no-op`() {
        renderer.highlightSearchMatch("c.xhtml", "some text", "rgba(255,0,0,0.3)")
        assertEquals(0, applied.size)
    }
}
