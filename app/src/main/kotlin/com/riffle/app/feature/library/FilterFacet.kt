package com.riffle.app.feature.library

import com.riffle.core.domain.LibraryItem

/**
 * A single metadata dimension the [FilteredBooksScreen] can filter the current Library by. Each
 * value on the Library Item Detail Screen that is tappable maps to one of these (ADR 0027). The
 * match is computed client-side over the already-synced Library Items — no server filter call.
 */
enum class FacetType {
    AUTHOR,
    GENRE,
    YEAR,
    LANGUAGE,
    READALOUD,
}

/**
 * Whether [item] belongs in a [FilteredBooksScreen] for the given facet.
 *
 * - AUTHOR matches on a name **token**: the flattened `author` string is split on ", " (the
 *   separator ABS uses to join multiple authors) so a co-authored book matches each of its authors.
 * - GENRE / YEAR / LANGUAGE are exact matches against the item's value(s).
 * - READALOUD is set membership in [readaloudLinkedItemIds] (the badge entry point).
 */
fun facetMatches(
    item: LibraryItem,
    type: FacetType,
    value: String,
    readaloudLinkedItemIds: Set<String>,
): Boolean = when (type) {
    FacetType.AUTHOR -> item.author.split(", ").any { it == value }
    FacetType.GENRE -> item.genres.contains(value)
    FacetType.YEAR -> item.publishedYear == value
    FacetType.LANGUAGE -> item.language == value
    FacetType.READALOUD -> item.id in readaloudLinkedItemIds
}

/** The TopAppBar title for a facet screen. */
fun facetTitle(type: FacetType, value: String): String = when (type) {
    FacetType.READALOUD -> "Readalouds"
    else -> value
}
