package com.riffle.feature.library

import com.riffle.core.models.LibraryItem

enum class FacetType {
    AUTHOR,
    GENRE,
    YEAR,
    LANGUAGE,
    READALOUD,
}

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

fun facetTitle(type: FacetType, value: String): String = when (type) {
    FacetType.READALOUD -> "Readalouds"
    else -> value
}
