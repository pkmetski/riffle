package com.riffle.feature.reader

import com.riffle.core.models.EmphasisStyle
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuousAnnotationJsTest {

    @Test
    fun clearAnnotationHighlightsJsRemovesAnnotationMarks() {
        val js = ContinuousAnnotationJs.CLEAR_ANNOTATION_HIGHLIGHTS_JS
        assertContains(js, "data-riffle-ann")
        assertContains(js, "data-riffle-note-glyph")
    }

    @Test
    fun clearSearchHighlightsJsRemovesSearchMarks() {
        val js = ContinuousAnnotationJs.CLEAR_SEARCH_HIGHLIGHTS_JS
        assertContains(js, "data-riffle-si")
        assertContains(js, "data-riffle-sa")
    }

    @Test
    fun clearHighlightJsTargetsReadaloudMark() {
        val js = ContinuousAnnotationJs.CLEAR_HIGHLIGHT_JS
        assertContains(js, "_riffle_hl")
    }

    @Test
    fun applyAnnotationHighlightsJsEmptyListProducesClearJs() {
        val js = ContinuousAnnotationJs.applyAnnotationHighlightsJs(emptyList())
        assertContains(js, "data-riffle-ann")
        assertFalse(js.contains("locateRanges"), "empty list must not emit locate-ranges block")
    }

    @Test
    fun applyAnnotationHighlightsJsSingleAnnotation() {
        val ann = AnnotationHighlight(
            id = "ann-1",
            text = "quick brown fox",
            cssColor = "rgba(255,235,59,0.45)",
            hasNote = false,
            before = "The ",
            after = " jumps",
        )
        val js = ContinuousAnnotationJs.applyAnnotationHighlightsJs(listOf(ann))
        assertContains(js, "ann-1")
        assertContains(js, "quick brown fox")
        assertContains(js, "rgba(255,235,59,0.45)")
        assertContains(js, "data-riffle-ann")
    }

    @Test
    fun applyAnnotationHighlightsJsWithNote() {
        val ann = AnnotationHighlight(
            id = "ann-note",
            text = "lazy dog",
            cssColor = "rgba(33,150,243,0.45)",
            hasNote = true,
        )
        val js = ContinuousAnnotationJs.applyAnnotationHighlightsJs(listOf(ann))
        assertContains(js, "data-riffle-note-glyph")
    }

    @Test
    fun applyAnnotationHighlightsJsWithEmphasis() {
        val ann = AnnotationHighlight(
            id = "ann-em",
            text = "bold italic",
            cssColor = "rgba(255,0,0,0.4)",
            emphasisStyles = setOf(EmphasisStyle.BOLD, EmphasisStyle.ITALIC),
        )
        val js = ContinuousAnnotationJs.applyAnnotationHighlightsJs(listOf(ann))
        assertContains(js, "bold")
        assertContains(js, "italic")
    }

    @Test
    fun applyAnnotationHighlightsJsSuppressMarkClickEmitsSuppressFlag() {
        val ann = AnnotationHighlight(
            id = "ann-sup",
            text = "some text",
            cssColor = "rgba(100,100,100,0.4)",
            suppressMarkClick = true,
        )
        val js = ContinuousAnnotationJs.applyAnnotationHighlightsJs(listOf(ann))
        assertContains(js, "s:1")
        assertFalse(js.contains("s:0"))
    }

    @Test
    fun highlightIdJsReferencesFragment() {
        val js = ContinuousAnnotationJs.highlightIdJs("cd-42", "rgba(255,152,0,0.5)")
        assertContains(js, "cd-42")
        assertContains(js, "_riffle_hl")
    }

    @Test
    fun highlightIdJsBlankReturnsCleanClearJs() {
        val js = ContinuousAnnotationJs.highlightIdJs("", "rgba(255,152,0,0.5)")
        assertContains(js, "_riffle_hl")
        assertFalse(js.contains("getElementById('')"), "blank id must not produce an empty getElementById call")
    }

    @Test
    fun highlightTextJsReferencesText() {
        val js = ContinuousAnnotationJs.highlightTextJs("example sentence", "rgba(255,152,0,0.5)")
        assertContains(js, "example sentence")
        assertContains(js, "_riffle_hl")
    }

    @Test
    fun applyAnnotationsFromInputsEmptyListDelegates() {
        val js = ContinuousAnnotationJs.applyAnnotationsFromInputs(emptyList())
        assertContains(js, "data-riffle-ann")
    }

    @Test
    fun applyAnnotationsFromInputsDecodesBoldToken() {
        val input = ContinuousAnnotationInput(
            id = "ann-bold",
            text = "some text",
            cssColor = "rgba(0,0,0,0.4)",
            emphasisTokens = "bold",
        )
        val js = ContinuousAnnotationJs.applyAnnotationsFromInputs(listOf(input))
        assertContains(js, "bold")
    }

    @Test
    fun applyAnnotationsFromInputsProducesAnnotationJs() {
        val input = ContinuousAnnotationInput(
            id = "ann-swift",
            text = "fox",
            cssColor = "rgba(255,235,59,0.45)",
        )
        val js = ContinuousAnnotationJs.applyAnnotationsFromInputs(listOf(input))
        assertContains(js, "ann-swift")
        assertContains(js, "fox")
    }
}
