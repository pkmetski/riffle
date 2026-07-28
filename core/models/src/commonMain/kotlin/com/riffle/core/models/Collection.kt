package com.riffle.core.models

data class Collection(
    val id: String,
    val libraryId: String,
    val name: String,
    val bookCount: Int,
)
