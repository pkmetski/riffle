package com.riffle.core.catalog

/**
 * A server-side facet the Catalog can filter [Catalog.browse] by. Distinct from the client-side
 * [FilteredBooksScreen] facets (ADR 0027) which filter already-synced items in memory: a server-side
 * facet is a hint passed back to [Catalog.browse] via [FacetSelection] and executes on the origin
 * (e.g. Chitanka fetches `/texts/label/{key}`). See ADR 0042.
 *
 * Rendered by the Library screen as a horizontal chip strip below the search bar; a Catalog with no
 * server-side facets returns an empty list from [Catalog.listFacets] and no strip appears.
 *
 * [key] is Source-local — the Catalog interprets its own keys; consumers treat it as opaque.
 * [label] is the display string (already localised by the Source — e.g. Cyrillic for Chitanka).
 * [sortOrder] is a per-Catalog stable ordering hint; consumers sort ascending on it.
 */
data class CatalogFacet(
    val key: String,
    val label: String,
    val sortOrder: Int = 0,
)

/**
 * A single selection from a Catalog's facet list, passed to [Catalog.browse] to constrain results.
 * Opaque to Sources that do not recognise the [key] — non-facet Catalogs ignore the parameter.
 */
data class FacetSelection(
    val key: String,
)
