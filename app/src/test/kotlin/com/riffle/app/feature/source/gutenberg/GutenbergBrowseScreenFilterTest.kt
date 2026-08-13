package com.riffle.app.feature.source.gutenberg

import com.riffle.core.catalog.CatalogFacet
import org.junit.Assert.assertEquals
import org.junit.Test

class GutenbergBrowseScreenFilterTest {

    @Test
    fun `language facets are separated from topic facets for dropdown rendering`() {
        val facets = listOf(
            CatalogFacet(key = "topic:fiction", label = "Fiction"),
            CatalogFacet(key = "language:en", label = "English"),
            CatalogFacet(key = "topic:history", label = "History"),
            CatalogFacet(key = "language:fr", label = "French"),
        )

        assertEquals(
            listOf("language:en", "language:fr"),
            gutenbergLanguageFacets(facets).map { it.key },
        )
        assertEquals(
            listOf("topic:fiction", "topic:history"),
            gutenbergTopicFacets(facets).map { it.key },
        )
    }
}
