package com.riffle.app.feature.reader

import com.riffle.core.domain.AnnotationStore
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the heal loop [EpubReaderViewModel.noteBookBodyFontFamily] runs on the first real
 * body-font report per book: EVERY bare-generic-stamped row must converge to the probed
 * publisher face, not only rows carrying the legacy `serif` sentinel.
 *
 * Regression: elided-view-always-sans (*Taking Charge of ADHD*). A highlight created while the
 * reader Font pref was Sans stored `sans-serif`; the pre-fix heal only targeted the `serif`
 * sentinel, so the polluted row survived every subsequent Original-mode open and kept winning
 * the plurality vote — the book's elided view stayed pinned to sans-serif forever.
 */
class HealGenericOriginFontsTest {

    /** id → originFontFamily, healed with the same exact-match semantics as the Room query. */
    private class RecordingStore(
        val fonts: MutableMap<String, String?>,
    ) : AnnotationStore {
        val healedSentinels = mutableListOf<String>()

        override suspend fun healSentinelOriginFontFamily(
            sourceId: String,
            itemId: String,
            sentinel: String,
            fontFamily: String,
        ): Int {
            if (fontFamily.isBlank() || sentinel.isBlank() || fontFamily == sentinel) return 0
            healedSentinels += sentinel
            var changed = 0
            for ((id, font) in fonts) {
                if (font == sentinel) {
                    fonts[id] = fontFamily
                    changed++
                }
            }
            return changed
        }

        override fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>> = error("unused")
        override fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>> = error("unused")
        override fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>> = error("unused")
        override fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>> = error("unused")
        override suspend fun createHighlight(
            sourceId: String,
            itemId: String,
            cfi: String,
            textSnippet: String,
            chapterHref: String,
            textBefore: String,
            textAfter: String,
            color: String,
            spineIndex: Int,
            progression: Double,
            embeddedFigures: List<EmbeddedFigure>?,
            originFontFamily: String,
            textSnippetHtml: String?,
        ): Annotation = error("unused")
        override suspend fun createBookmark(
            sourceId: String,
            itemId: String,
            cfi: String,
            textSnippet: String,
            chapterHref: String,
            spineIndex: Int,
            progression: Double,
            bookmarkTitle: String,
            originFontFamily: String,
            fragmentAnchor: String?,
        ): Annotation = error("unused")
        override suspend fun createImageAnnotation(
            sourceId: String,
            itemId: String,
            cfi: String,
            textSnippet: String,
            chapterHref: String,
            spineIndex: Int,
            progression: Double,
            imageHref: String?,
            imageSvg: String?,
            imageBytes: String?,
            color: String,
        ): Annotation = error("unused")
        override suspend fun backfillNullOriginFontFamily(
            sourceId: String,
            itemId: String,
            fontFamily: String,
        ): Int = error("unused")
        override suspend fun upgradeImageToCaptionHighlight(
            id: String,
            cfi: String,
            textSnippet: String,
            textBefore: String,
            textAfter: String,
            figure: EmbeddedFigure,
        ): Annotation? = error("unused")
        override suspend fun mergeFiguresIntoHighlight(
            id: String,
            newFigures: List<EmbeddedFigure>,
        ): Annotation? = error("unused")
        override suspend fun delete(id: String) = error("unused")
        override suspend fun recolor(id: String, color: String) = error("unused")
        override suspend fun updateNote(id: String, note: String?) = error("unused")
        override suspend fun renameBookmark(id: String, title: String) = error("unused")
        override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation? =
            error("unused")
        override suspend fun findImageAnnotationForFigure(
            sourceId: String,
            itemId: String,
            chapterHref: String,
            imageHref: String?,
            imageSvg: String?,
        ): Annotation? = error("unused")
    }

    @Test
    fun healsEveryBareGenericKeywordNotJustTheSerifSentinel() = runTest {
        val store = RecordingStore(
            mutableMapOf(
                "polluted-sans" to "sans-serif",
                "polluted-sans-quoted" to "\"sans-serif\"",
                "polluted-serif-squoted" to "'serif'",
                "legacy-sentinel" to "serif",
                "polluted-mono" to "monospace",
                "real-face" to "Minion",
                "stack" to "Nimbusromno9l, serif",
                "unprobed" to null,
            ),
        )

        val healed = healGenericOriginFonts(store, "S1", "B1", "Minion Pro")

        assertEquals("all five generic-stamped rows must be healed", 5, healed)
        assertEquals(
            "the sans-serif row (elided-view-always-sans regression) converges to the probed face",
            "Minion Pro",
            store.fonts["polluted-sans"],
        )
        assertEquals(
            "continuous mode quotes stack entries — the double-quoted variant heals too",
            "Minion Pro",
            store.fonts["polluted-sans-quoted"],
        )
        assertEquals(
            "single-quoted variant heals too",
            "Minion Pro",
            store.fonts["polluted-serif-squoted"],
        )
        assertEquals("the serif sentinel row still heals", "Minion Pro", store.fonts["legacy-sentinel"])
        assertEquals("monospace pollution heals too", "Minion Pro", store.fonts["polluted-mono"])
        assertEquals("a real captured face is never rewritten", "Minion", store.fonts["real-face"])
        assertEquals(
            "a stack with a generic fallback is a real face and is never rewritten",
            "Nimbusromno9l, serif",
            store.fonts["stack"],
        )
        assertEquals("null rows are the backfill path's job, not heal's", null, store.fonts["unprobed"])
    }
}
