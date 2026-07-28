package com.riffle.core.network

data class NetworkStorytellerBook(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val isbn: String? = null,
    val asin: String? = null,
)