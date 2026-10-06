package com.riffle.feature.source.ui.websource

import com.riffle.core.catalog.CatalogFacet
import kotlin.test.Test
import kotlin.test.assertEquals

class GutenbergBrowseScreenFilterTest {

    @Test
    fun languageFacetsAreSeparatedFromTopicFacetsForDropdownRendering() {
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
