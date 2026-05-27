package com.riffle.core.network.model

import kotlinx.serialization.Serializable

/**
 * POST /api/collections body. `books` has no default value because kotlinx serialization
 * omits default-equal fields when `encodeDefaults = false` (the project default), and ABS
 * requires the field to always be present on the wire.
 */
@Serializable
internal data class AbsCreateCollectionRequest(
    val libraryId: String,
    val name: String,
    val books: List<String>,
)

/** POST /api/collections/:id/book body. */
@Serializable
internal data class AbsCollectionBookRequest(
    val id: String,
)
