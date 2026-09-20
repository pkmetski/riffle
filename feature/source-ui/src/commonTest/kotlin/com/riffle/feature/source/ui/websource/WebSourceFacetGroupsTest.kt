package com.riffle.feature.source.ui.websource

import com.riffle.core.catalog.CatalogFacet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The facet-group split each unbounded source's chip strip is built from.
 *
 * These lived as `internal` helpers inside `:app`'s per-source browse screens, so the iOS browse
 * screen would have had to re-derive the `language:` / `lang:` / `country:` prefixes — the exact
 * private-platform-copy drift AGENTS.md forbids. Now shared, and pinned here in `commonTest` so
 * the assertions run on `iosSimulatorArm64` as well as the JVM.
 */
class WebSourceFacetGroupsTest {

    private val gutenbergFacets = listOf(
        CatalogFacet(key = "topic:fiction", label = "Fiction"),
        CatalogFacet(key = "language:en", label = "English"),
        CatalogFacet(key = "topic:history", label = "History"),
        CatalogFacet(key = "language:fr", label = "French"),
    )

    private val radioEsFacets = listOf(
        CatalogFacet(key = "category:news", label = "News"),
        CatalogFacet(key = "lang:es", label = "Spanish"),
        CatalogFacet(key = "country:es", label = "Spain"),
        CatalogFacet(key = "category:music", label = "Music"),
        CatalogFacet(key = "lang:en", label = "English"),
    )

    @Test
    fun gutenbergSplitsLanguagesOutOfTheChipStrip() {
        assertEquals(
            listOf("language:en", "language:fr"),
            gutenbergLanguageFacets(gutenbergFacets).map { it.key },
        )
        assertEquals(
            listOf("topic:fiction", "topic:history"),
            gutenbergTopicFacets(gutenbergFacets).map { it.key },
        )
    }

    @Test
    fun radioEsSplitsLanguagesAndCountriesOutOfTheChipStrip() {
        assertEquals(listOf("lang:es", "lang:en"), radioEsLanguageFacets(radioEsFacets).map { it.key })
        assertEquals(listOf("country:es"), radioEsCountryFacets(radioEsFacets).map { it.key })
        assertEquals(
            listOf("category:news", "category:music"),
            radioEsCategoryFacets(radioEsFacets).map { it.key },
        )
    }

    @Test
    fun everyFacetLandsInExactlyOneGroup() {
        // The strip renders plain chips plus the dropdowns; a facet in neither group, or in two,
        // is a facet the user either cannot reach or sees twice.
        val gutenberg = gutenbergLanguageFacets(gutenbergFacets) + gutenbergTopicFacets(gutenbergFacets)
        assertEquals(gutenbergFacets.map { it.key }.sorted(), gutenberg.map { it.key }.sorted())

        val radioEs = radioEsLanguageFacets(radioEsFacets) +
            radioEsCountryFacets(radioEsFacets) +
            radioEsCategoryFacets(radioEsFacets)
        assertEquals(radioEsFacets.map { it.key }.sorted(), radioEs.map { it.key }.sorted())
    }
}
