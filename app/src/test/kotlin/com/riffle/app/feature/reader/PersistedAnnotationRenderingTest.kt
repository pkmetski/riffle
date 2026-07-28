package com.riffle.app.feature.reader

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.models.HighlightColor
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator

class PersistedAnnotationRenderingTest {

    @Test
    fun `note glyphs are applied after annotation DOM mutation and decoration measurement`() = runTest {
        val calls = mutableListOf<String>()
        val renderer = RecordingHighlightRenderer(calls)

        renderPersistedAnnotations(renderer, emptyList())

        assertEquals(listOf("annotations", "note-glyphs"), calls)
    }

    @Test
    fun `screen owns one persisted annotation effect so render passes cannot race`() {
        val source = locateScreenSource().readText()
        val block = source.substringAfter("// ---- Persisted highlights (annotations + note glyphs)")
            .substringBefore("// ---- Figure borders")

        assertEquals(
            "persisted highlights and note glyphs must share one Compose effect",
            1,
            Regex("""(?m)^\s*LaunchedEffect\(""").findAll(block).count(),
        )
        assertTrue(
            "the shared effect must use the ordered rendering seam",
            block.contains("renderPersistedAnnotations(highlightRenderer, highlightRenders)"),
        )
        assertFalse(
            "the screen must not launch the note-glyph pass independently",
            block.contains("highlightRenderer.applyNoteGlyphs(highlightRenders)"),
        )
    }

    private fun locateScreenSource(): File =
        sequenceOf(
            File("src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt"),
            File("app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt"),
        ).firstOrNull(File::exists)
            ?: error("EpubReaderScreen.kt not found from ${File(".").absolutePath}")

    private class RecordingHighlightRenderer(
        private val calls: MutableList<String>,
    ) : HighlightRenderer {
        override suspend fun applySentenceHighlight(
            fragmentRef: String?,
            quotes: Map<String, SentenceQuote>,
            color: HighlightColor,
        ) = Unit

        override suspend fun applyAnnotations(
            renders: List<EpubReaderViewModel.HighlightRender>,
        ) {
            calls += "annotations"
        }

        override suspend fun applyNoteGlyphs(
            renders: List<EpubReaderViewModel.HighlightRender>,
        ) {
            calls += "note-glyphs"
        }

        override suspend fun applySearch(results: List<Locator>, activeIndex: Int) = Unit

        override fun highlightSearchMatch(href: String, text: String) = Unit
    }
}
