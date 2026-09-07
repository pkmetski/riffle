package com.riffle.app.feature.library

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression coverage for the O'Reilly ABS-upload author drop: `getItem` on the source can return a
 * blank author (O'Reilly's book-detail metadata carries none), and the upload must fall back to the
 * author captured from the browse/search listing on the stored library item.
 */
class WithFallbackAuthorTest {

    private fun item(author: String) = CatalogItem(
        id = "9781098122584",
        rootId = "books",
        title = "Some Book",
        author = author,
        coverUrl = null,
        ebookFormat = BookFormat.Epub,
    )

    @Test
    fun `blank fetched author falls back to the stored library author`() {
        val merged = withFallbackAuthor(item(author = ""), storedAuthor = "Al Sweigart")
        assertEquals("Al Sweigart", merged.author)
    }

    @Test
    fun `a populated fetched author is preserved over the stored one`() {
        val fetched = item(author = "Fresh Author")
        val merged = withFallbackAuthor(fetched, storedAuthor = "Stale Author")
        assertSame(fetched, merged)
        assertEquals("Fresh Author", merged.author)
    }

    @Test
    fun `both blank stays blank`() {
        val merged = withFallbackAuthor(item(author = ""), storedAuthor = "")
        assertEquals("", merged.author)
    }
}
