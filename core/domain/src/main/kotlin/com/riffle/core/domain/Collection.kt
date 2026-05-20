package com.riffle.core.domain

data class Collection(
    val id: String,
    val libraryId: String,
    val name: String,
    val bookCount: Int,
)
