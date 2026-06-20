package com.riffle.core.domain

/** A user bookmark in an audiobook: a titled book-absolute position. */
data class AudiobookBookmark(
    val id: String,
    val serverId: String,
    val itemId: String,
    val positionSec: Double,
    val title: String,
    val createdAt: Long,
)
