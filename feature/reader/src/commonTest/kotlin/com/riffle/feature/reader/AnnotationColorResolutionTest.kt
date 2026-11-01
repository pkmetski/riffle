package com.riffle.feature.reader

import com.riffle.core.models.HighlightColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnotationColorResolutionTest {

    @Test
    fun draft_preSelectsDefaultColor_onFirstBookOpen() {
        val result = resolveAnnotationSheetColor(
            persistedColor = null,
            lastUsedHighlightColor = HighlightColor.DEFAULT,
            lastUsedColorIsNone = false,
        )
        assertEquals(HighlightColor.DEFAULT, result)
    }

    @Test
    fun draft_preSelectsLastUsedColor() {
        val result = resolveAnnotationSheetColor(
            persistedColor = null,
            lastUsedHighlightColor = HighlightColor.GREEN,
            lastUsedColorIsNone = false,
        )
        assertEquals(HighlightColor.GREEN, result)
    }

    @Test
    fun draft_preSelectsNone_whenLastUsedWasNone() {
        val result = resolveAnnotationSheetColor(
            persistedColor = null,
            lastUsedHighlightColor = HighlightColor.YELLOW,
            lastUsedColorIsNone = true,
        )
        assertNull(result)
    }

    @Test
    fun existingAnnotation_reflectsPersistedColor() {
        val result = resolveAnnotationSheetColor(
            persistedColor = HighlightColor.BLUE.token,
            lastUsedHighlightColor = HighlightColor.GREEN,
            lastUsedColorIsNone = false,
        )
        assertEquals(HighlightColor.BLUE, result)
    }

    @Test
    fun existingAnnotation_emptyColorShowsNone() {
        val result = resolveAnnotationSheetColor(
            persistedColor = "",
            lastUsedHighlightColor = HighlightColor.GREEN,
            lastUsedColorIsNone = false,
        )
        assertNull(result)
    }

    @Test
    fun existingAnnotation_ignoresLastUsedState() {
        val result = resolveAnnotationSheetColor(
            persistedColor = HighlightColor.YELLOW.token,
            lastUsedHighlightColor = HighlightColor.GREEN,
            lastUsedColorIsNone = true,
        )
        assertEquals(HighlightColor.YELLOW, result)
    }
}
