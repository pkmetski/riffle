package com.riffle.feature.source.ui.websource

import com.riffle.core.catalog.CatalogFacet

/**
 * How each unbounded source's flat facet list splits into the chip strip's groups.
 *
 * Shared because both hosts render the same strip: they used to live as `internal` helpers inside
 * `:app`'s per-source browse screens, so the iOS browse screen would have had to re-derive the
 * same prefixes and could silently disagree about them.
 */
private const val GUTENBERG_LANGUAGE_FACET_PREFIX = "language:"
private const val RADIO_ES_LANGUAGE_FACET_PREFIX = "lang:"
private const val RADIO_ES_COUNTRY_FACET_PREFIX = "country:"

fun gutenbergLanguageFacets(facets: List<CatalogFacet>): List<CatalogFacet> =
    facets.filter { it.key.startsWith(GUTENBERG_LANGUAGE_FACET_PREFIX) }

fun gutenbergTopicFacets(facets: List<CatalogFacet>): List<CatalogFacet> =
    facets.filterNot { it.key.startsWith(GUTENBERG_LANGUAGE_FACET_PREFIX) }

fun radioEsLanguageFacets(facets: List<CatalogFacet>): List<CatalogFacet> =
    facets.filter { it.key.startsWith(RADIO_ES_LANGUAGE_FACET_PREFIX) }

fun radioEsCategoryFacets(facets: List<CatalogFacet>): List<CatalogFacet> =
    facets.filterNot { it.key.startsWith(RADIO_ES_LANGUAGE_FACET_PREFIX) }
        .filterNot { it.key.startsWith(RADIO_ES_COUNTRY_FACET_PREFIX) }

fun radioEsCountryFacets(facets: List<CatalogFacet>): List<CatalogFacet> =
    facets.filter { it.key.startsWith(RADIO_ES_COUNTRY_FACET_PREFIX) }
