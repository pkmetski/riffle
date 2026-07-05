package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationsPanelMaxLinesTest {

    @Test
    fun bookmarkTitleIsCappedToTwoLines() {
        assertEquals(2, maxLinesForAnnotationTitle(AnnotationEntity.TYPE_BOOKMARK))
    }

    @Test
    fun highlightSnippetGetsMoreLinesThanBookmark() {
        val highlight = maxLinesForAnnotationTitle(AnnotationEntity.TYPE_HIGHLIGHT)
        val bookmark = maxLinesForAnnotationTitle(AnnotationEntity.TYPE_BOOKMARK)
        assertTrue("highlight max ($highlight) must exceed bookmark max ($bookmark)", highlight > bookmark)
    }

    @Test
    fun highlightSnippetStillHasFiniteMax() {
        val max = maxLinesForAnnotationTitle(AnnotationEntity.TYPE_HIGHLIGHT)
        assertTrue("highlight max should be a sensible cap, was $max", max in 3..20)
    }
}
