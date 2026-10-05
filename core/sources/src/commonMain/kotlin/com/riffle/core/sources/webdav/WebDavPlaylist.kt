package com.riffle.core.sources.webdav

import kotlinx.serialization.Serializable

/** On-wire and in-memory representation of a single playlist stored as a WebDAV JSON file. */
@Serializable
data class WebDavPlaylist(
    val id: String,
    val name: String,
    val libraryId: String,
    val itemIds: List<String>,
    val lastUpdate: Long,
)
