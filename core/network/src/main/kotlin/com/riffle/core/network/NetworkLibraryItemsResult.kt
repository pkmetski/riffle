package com.riffle.core.network

data class NetworkLibraryItem(
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val readingProgress: Float,
    val isSupported: Boolean,
    val ebookFileIno: String? = null,
)

sealed class NetworkItemEbookInoResult {
    data class Success(val ino: String) : NetworkItemEbookInoResult()
    data class NetworkError(val cause: Throwable) : NetworkItemEbookInoResult()
}

sealed class NetworkEpubDownloadResult {
    data class Success(val bytes: ByteArray) : NetworkEpubDownloadResult()
    data class NetworkError(val cause: Throwable) : NetworkEpubDownloadResult()
}

sealed class NetworkLibraryItemsResult {
    data class Success(val items: List<NetworkLibraryItem>) : NetworkLibraryItemsResult()
    data class NetworkError(val cause: Throwable) : NetworkLibraryItemsResult()
}
