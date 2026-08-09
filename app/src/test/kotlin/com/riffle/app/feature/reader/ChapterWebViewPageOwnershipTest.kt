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

    @Test
    fun `allows any page load when no chapter url has been set yet`() {
        // expectedChapterUrl == null means loadChapter() has not been called on this WebView yet;
        // any onPageFinished callback (including "about:blank" from loadDataWithBaseURL) should pass.
        assertTrue(isCurrentChapterPage("about:blank", null))
        assertTrue(isCurrentChapterPage("https://readium_package/OEBPS/ch01.html", null))
        assertTrue(isCurrentChapterPage(null, null))
    }
}
