package com.riffle.feature.reader

import com.riffle.core.database.AnnotationEntity
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.Test

class AnnotationsPanelMaxLinesTest {

    @Test
    fun bookmarkTitleIsCappedToTwoLines() {
        assertEquals(2, maxLinesForAnnotationTitle(AnnotationEntity.TYPE_BOOKMARK))
    }

    @Test
    fun highlightSnippetGetsMoreLinesThanBookmark() {
        val highlight = maxLinesForAnnotationTitle(AnnotationEntity.TYPE_HIGHLIGHT)
        val bookmark = maxLinesForAnnotationTitle(AnnotationEntity.TYPE_BOOKMARK)
        assertTrue(highlight > bookmark, "highlight max ($highlight) must exceed bookmark max ($bookmark)")
    }

    @Test
    fun highlightSnippetStillHasFiniteMax() {
        val max = maxLinesForAnnotationTitle(AnnotationEntity.TYPE_HIGHLIGHT)
        assertTrue(max in 3..20, "highlight max should be a sensible cap, was $max")
    }
}
