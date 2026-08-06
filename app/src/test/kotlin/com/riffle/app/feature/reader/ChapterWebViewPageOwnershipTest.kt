package com.riffle.app.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterWebViewPageOwnershipTest {

    @Test
    fun `ignores recycled about blank completion after view is rebound to a chapter`() {
        val expected = "https://readium_package/OEBPS/ch06.html"

        assertFalse(isCurrentChapterPage("about:blank", expected))
        assertTrue(isCurrentChapterPage(expected, expected))
    }
}
