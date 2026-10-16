package com.riffle.feature.navigation

import com.riffle.core.domain.ApplicationScope
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.FacetType
import com.riffle.feature.library.LibrarySectionType

sealed interface LibraryNav {
    data object Items : LibraryNav
    data class Section(val sectionType: LibrarySectionType) : LibraryNav

    data class ItemDetail(val itemId: String, val sourceId: String?) : LibraryNav

    data class ElidedReader(val itemId: String, val sourceId: String?) : LibraryNav
    /** Opens the full EPUB reader at [locatorJson], loading the item by id first. Used by annotation search so the user lands on the annotated page rather than the highlights view. */
    data class AnnotationReader(val itemId: String, val sourceId: String?, val locatorJson: String) : LibraryNav
    data class SeriesDetail(val seriesId: String, val seriesLibraryId: String, val seriesName: String) : LibraryNav
    data class CollectionDetail(val collectionId: String, val collectionLibraryId: String, val collectionName: String) : LibraryNav

    data class PlaylistDetail(
        val playlistId: String,
        val playlistName: String,
        val playlistLibraryId: String,
    ) : LibraryNav

    data class FilteredBooks(
        val facetLibraryId: String,
        val facetType: FacetType,
        val facetValue: String,
    ) : LibraryNav

    data class AnnotationSearch(
        val searchLibraryId: String,
        val query: String,
    ) : LibraryNav

    sealed interface ReaderDestination : LibraryNav {
        val item: LibraryItem
    }

    data class Reader(
        override val item: LibraryItem,
        val initialLocatorJson: String? = null,
        val startHref: String? = null,
        val startReadaloudAtSec: Double = -1.0,
    ) : ReaderDestination
    data class PdfReader(override val item: LibraryItem) : ReaderDestination
    data class CbzReader(override val item: LibraryItem) : ReaderDestination

    data class AudiobookPlayer(
        override val item: LibraryItem,
        val playlistId: String? = null,
        val playlistLibraryId: String? = null,
        val startAtSec: Double = -1.0,
    ) : ReaderDestination

    data class AudiobookBookmark(
        val itemId: String,
        val sourceId: String?,
        val startAtSec: Double,
    ) : LibraryNav
}

fun readerNavForItem(item: LibraryItem): LibraryNav.ReaderDestination? = when {
    item.isListenable -> LibraryNav.AudiobookPlayer(item)
    item.ebookFormat == EbookFormat.Pdf -> LibraryNav.PdfReader(item)
    item.ebookFormat == EbookFormat.Cbz -> LibraryNav.CbzReader(item)
    item.isReadable -> LibraryNav.Reader(item)
    else -> null
}

fun readNavForItem(item: LibraryItem): LibraryNav.ReaderDestination? = when {
    item.ebookFormat == EbookFormat.Pdf -> LibraryNav.PdfReader(item)
    item.ebookFormat == EbookFormat.Cbz -> LibraryNav.CbzReader(item)
    item.isReadable -> LibraryNav.Reader(item)
    item.isListenable -> LibraryNav.AudiobookPlayer(item)
    else -> null
}

fun readNavForItemAtHref(item: LibraryItem, tocHref: String): LibraryNav.Reader? {
    if (!item.isReadable || item.ebookFormat == EbookFormat.Pdf || item.ebookFormat == EbookFormat.Cbz) return null
    return LibraryNav.Reader(item, startHref = tocHref)
}

fun openItemForReading(
    item: LibraryItem,
    applicationScope: ApplicationScope,
    recordItemOpened: suspend (itemId: String) -> Unit,
): LibraryNav.ReaderDestination? {
    val destination = readerNavForItem(item) ?: return null
    applicationScope.launchSurvivable { runCatching { recordItemOpened(item.id) } }
    return destination
}

fun openReadItemForReading(
    item: LibraryItem,
    applicationScope: ApplicationScope,
    recordItemOpened: suspend (itemId: String) -> Unit,
): LibraryNav.ReaderDestination? {
    val destination = readNavForItem(item) ?: return null
    applicationScope.launchSurvivable { runCatching { recordItemOpened(item.id) } }
    return destination
}

fun openReadItemAtHref(
    item: LibraryItem,
    tocHref: String,
    applicationScope: ApplicationScope,
    recordItemOpened: suspend (itemId: String) -> Unit,
): LibraryNav.ReaderDestination? {
    val destination = readNavForItemAtHref(item, tocHref) ?: return null
    applicationScope.launchSurvivable { runCatching { recordItemOpened(item.id) } }
    return destination
}

fun playlistPlayerNav(
    item: LibraryItem,
    from: LibraryNav.PlaylistDetail,
): LibraryNav.AudiobookPlayer = LibraryNav.AudiobookPlayer(
    item = item,
    playlistId = from.playlistId,
    playlistLibraryId = from.playlistLibraryId,
)

fun playlistAdvanceNav(
    next: LibraryItem?,
    from: LibraryNav.AudiobookPlayer,
): LibraryNav = when {
    next == null -> LibraryNav.Items
    else -> LibraryNav.AudiobookPlayer(
        item = next,
        playlistId = from.playlistId,
        playlistLibraryId = from.playlistLibraryId,
    )
}

fun isAlreadyAtPlayerDestination(stack: List<LibraryNav>, incoming: LibraryNav): Boolean {
    val top = stack.lastOrNull() ?: return false
    return when {
        top is LibraryNav.AudiobookPlayer && incoming is LibraryNav.AudiobookPlayer ->
            top.item.id == incoming.item.id
        top is LibraryNav.Reader && incoming is LibraryNav.Reader ->
            top.item.id == incoming.item.id
        else -> false
    }
}
