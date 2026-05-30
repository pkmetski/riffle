package com.riffle.core.network.model

import kotlinx.serialization.Serializable

/**
 * POST /api/playlists body. `items` has no default value because kotlinx serialization
 * omits default-equal fields when `encodeDefaults = false` (the project default), and ABS
 * requires the field to always be present on the wire (even as an empty list).
 */
@Serializable
internal data class AbsCreatePlaylistRequest(
    val libraryId: String,
    val name: String,
    val items: List<AbsPlaylistItemRequest>,
)

/** POST /api/playlists/:id/item body. */
@Serializable
internal data class AbsPlaylistItemRequest(
    val libraryItemId: String,
)
