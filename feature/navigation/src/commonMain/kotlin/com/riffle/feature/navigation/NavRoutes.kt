package com.riffle.feature.navigation

import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SourceType
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.library.urlFormEncode

// Reader route templates. They live here (rather than next to the Android NavHost) because
// [isReaderRoute] — the predicate every host uses to decide whether the reader chrome is
// on screen — is shared logic and must derive from the same templates on both platforms.
const val EPUB_READER =
    "epub_reader/{itemId}?startReadaloudAtSec={startReadaloudAtSec}&openAtCfi={openAtCfi}&openAnnotationId={openAnnotationId}&startTocHref={startTocHref}&source={source}&sourceId={sourceId}"
const val PDF_READER = "pdf_reader/{itemId}?sourceId={sourceId}"
const val CBZ_READER = "cbz_reader/{itemId}?sourceId={sourceId}"

/**
 * Where the "Add source" picker routes each [SourceType] to — delegated to the descriptor
 * (ADR 0053). Adding a new source needs no edit here.
 */
fun addSourceRouteFor(type: SourceType): String =
    WebSourceDescriptors.forTypeOrError(type).addRoute

/**
 * URL-encodes each path segment in a series-detail route. seriesId is encoded because chitanka
 * series ids contain slashes (`serie/foo` per ADR 0051) and would otherwise splay across the
 * fixed `series_detail/{libraryId}/{seriesId}/{seriesName}` template's `{seriesId}` slot,
 * producing the "destination cannot be found in the navigation graph" crash. Nav Compose
 * auto-decodes path arguments so the receiver (SeriesDetailViewModel) sees the original id.
 */
fun seriesDetailRoute(libraryId: String, seriesId: String, seriesName: String): String =
    "series_detail/$libraryId/${seriesId.urlFormEncode()}/${seriesName.urlFormEncode()}"

/** Same reasoning as [seriesDetailRoute] but for collection ids. */
fun collectionDetailRoute(libraryId: String, collectionId: String, collectionName: String): String =
    "collection_detail/$libraryId/${collectionId.urlFormEncode()}/${collectionName.urlFormEncode()}"

fun librarySectionRoute(
    libraryId: String,
    libraryName: String,
    sectionType: LibrarySectionType,
): String =
    "library_section/${libraryId.urlFormEncode()}/${libraryName.urlFormEncode()}/${sectionType.name}"

fun libraryItemDetailRoute(item: LibraryItem): String {
    val encodedId = item.id.urlFormEncode()
    val encodedSourceId = item.sourceId.urlFormEncode()
    return if (item.sourceId.isBlank()) {
        "library_item_detail/$encodedId"
    } else {
        "library_item_detail/$encodedId?sourceId=$encodedSourceId"
    }
}

/**
 * Dispatches to the correct library entry point for [sourceType]:
 *   - Room-mirrored catalogues (ABS, LocalFiles) → `library_items/…`
 *   - Unbounded catalogues → the source's dedicated browse screen. Each unbounded Source owns
 *     its own remote-browse route (Chitanka, Gutenberg, …) because their pagination, chip
 *     strip, and item-cards diverge enough that a single generic screen would leak per-Source
 *     branches everywhere.
 *
 * Adding a new unbounded Source means: (1) flip [SourceType.isUnboundedCatalog], (2) give its
 * descriptor a browse-route prefix, (3) register the composable at the NavHost. A null
 * [sourceType] (the active source hasn't resolved yet on cold start) falls back to
 * `library_items`; the drawer will correct on the next selection.
 */
fun libraryEntryRoute(sourceType: SourceType?, libraryId: String, libraryName: String): String {
    val encoded = libraryName.urlFormEncode()
    val prefix = sourceType
        ?.takeIf { it.isUnboundedCatalog }
        ?.let { WebSourceDescriptors.forType(it) }
        ?.browseRoutePrefix
    return if (prefix != null) "$prefix/$libraryId/$encoded" else "library_items/$libraryId/$encoded"
}

fun libraryEntryRoute(destination: HomeViewModel.StartDestination.Library): String =
    libraryEntryRoute(destination.sourceType, destination.libraryId, destination.libraryName)

fun isReaderRoute(route: String?): Boolean =
    route?.startsWith(EPUB_READER.substringBefore("{")) == true ||
        route?.startsWith(PDF_READER.substringBefore("{")) == true ||
        route?.startsWith(CBZ_READER.substringBefore("{")) == true
