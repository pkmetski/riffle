package com.riffle.core.network

import com.riffle.core.domain.EbookFormat

data class NetworkLibraryItem(
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val readingProgress: Float,
    val ebookFormat: EbookFormat,
    val ebookFileIno: String? = null,
    val description: String? = null,
    val seriesName: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
    val publisher: String? = null,
) {
    val isSupported: Boolean get() = ebookFormat != EbookFormat.Unsupported
}

sealed class NetworkItemEbookInoResult {
    data class Success(val ino: String) : NetworkItemEbookInoResult()
    data class NetworkError(val cause: Throwable) : NetworkItemEbookInoResult()
}

sealed class NetworkEpubDownloadResult {
    data class Success(val body: okhttp3.ResponseBody) : NetworkEpubDownloadResult()
    data class NetworkError(val cause: Throwable) : NetworkEpubDownloadResult()
}

sealed class NetworkLibraryItemsResult {
    data class Success(val items: List<NetworkLibraryItem>) : NetworkLibraryItemsResult()
    data class NetworkError(val cause: Throwable) : NetworkLibraryItemsResult()
}
