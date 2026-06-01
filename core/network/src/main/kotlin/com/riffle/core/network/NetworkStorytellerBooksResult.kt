package com.riffle.core.network

data class NetworkStorytellerBook(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val isbn: String? = null,
    val asin: String? = null,
)

sealed class NetworkStorytellerBooksResult {
    data class Success(val books: List<NetworkStorytellerBook>) : NetworkStorytellerBooksResult()
    data class NetworkError(val cause: Throwable) : NetworkStorytellerBooksResult()
}

sealed class NetworkStorytellerBookResult {
    data class Success(val book: NetworkStorytellerBook) : NetworkStorytellerBookResult()
    data class NotFound(val bookId: Long) : NetworkStorytellerBookResult()
    data class NetworkError(val cause: Throwable) : NetworkStorytellerBookResult()
}

sealed class NetworkStorytellerValidateResult {
    object Valid : NetworkStorytellerValidateResult()
    object Invalid : NetworkStorytellerValidateResult()
    data class NetworkError(val cause: Throwable) : NetworkStorytellerValidateResult()
}
