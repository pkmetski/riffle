package com.riffle.core.network.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/libraries/:libraryId/search?q=` payload. ABS groups hits by content type; only the
 * `book` group is relevant to a browsable Catalog. The nested `libraryItem` shares its shape with
 * `AbsLibraryItemsResponse.AbsLibraryItemDto` so the same mapper covers both endpoints.
 */
@Serializable
internal data class AbsLibrarySearchResponse(
    val book: List<AbsSearchBookHit> = emptyList(),
)

@Serializable
internal data class AbsSearchBookHit(
    val libraryItem: AbsLibraryItemsResponse.AbsLibraryItemDto,
    val matchKey: String? = null,
    val matchText: String? = null,
)
