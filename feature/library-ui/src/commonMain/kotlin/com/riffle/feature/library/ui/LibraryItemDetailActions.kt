package com.riffle.feature.library.ui

import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.FacetType

data class LibraryItemDetailActions(
    val onBack: () -> Unit,
    val onRead: (LibraryItem) -> Unit,
    val onListen: (LibraryItem) -> Unit,
    val onReadItemAtHref: (LibraryItem, String) -> Unit,
    val onListenItemAtSec: (LibraryItem, Double) -> Unit,
    val onFacetSelected: (libraryId: String, facet: FacetType, value: String) -> Unit,
    val onNavigateToSeries: (libraryId: String, seriesId: String, seriesName: String) -> Unit,
    val onEditMetadata: (() -> Unit)? = null,
)

fun LibraryItemDetailActions.probeFields(): List<Pair<String, Any>> = buildList {
    add("onBack" to onBack)
    add("onRead" to onRead)
    add("onListen" to onListen)
    add("onReadItemAtHref" to onReadItemAtHref)
    add("onListenItemAtSec" to onListenItemAtSec)
    add("onFacetSelected" to onFacetSelected)
    add("onNavigateToSeries" to onNavigateToSeries)
    onEditMetadata?.let { add("onEditMetadata" to it) }
}
