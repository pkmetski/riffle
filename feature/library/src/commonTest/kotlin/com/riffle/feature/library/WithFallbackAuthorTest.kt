package com.riffle.feature.library

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Regression coverage for the web-source author drop: `getItem` on some sources (O'Reilly's book
 * metadata) returns a blank author, and the ABS-upload path must fall back to the author captured
 * from the browse/search listing on the stored library item.
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
    fun blankFetchedAuthorFallsBackToStored() {
        val merged = withFallbackAuthor(item(author = ""), storedAuthor = "Al Sweigart")
        assertEquals("Al Sweigart", merged.author)
    }

    @Test
    fun populatedFetchedAuthorIsPreserved() {
        val fetched = item(author = "Fresh Author")
        val merged = withFallbackAuthor(fetched, storedAuthor = "Stale Author")
        assertSame(fetched, merged)
        assertEquals("Fresh Author", merged.author)
    }

    @Test
    fun bothBlankStaysBlank() {
        val merged = withFallbackAuthor(item(author = ""), storedAuthor = "")
        assertEquals("", merged.author)
    }
}
