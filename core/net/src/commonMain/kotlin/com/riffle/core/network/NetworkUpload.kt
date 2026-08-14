package com.riffle.core.network

import io.ktor.utils.io.ByteReadChannel

data class NetworkUploadMetadata(
    val title: String,
    val author: String,
    val folderId: String? = null,
    val series: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
    val isbn: String? = null,
    val asin: String? = null,
)

data class NetworkUploadPart(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    val provider: () -> ByteReadChannel,
)
