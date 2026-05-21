package com.riffle.core.domain

data class Series(
    val id: String,
    val libraryId: String,
    val name: String,
    val coverUrl: String?,
    val bookCount: Int,
)
