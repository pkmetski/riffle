package com.riffle.core.network.model

import kotlinx.serialization.Serializable

/** POST /api/collections body. */
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
