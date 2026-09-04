package com.riffle.core.network

interface KomgaCbzApi {
    suspend fun fetchCbzPageCount(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): Int

    suspend fun fetchCbzPage(
        baseUrl: String,
        bookId: String,
        pageIndex: Int,
        maxWidth: Int?,
        token: String,
        insecureAllowed: Boolean,
    ): ByteArray
}
