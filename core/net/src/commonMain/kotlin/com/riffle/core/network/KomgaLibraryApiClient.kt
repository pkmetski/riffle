package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

class KomgaLibraryApiClient(
    private val httpClient: HttpClient,
) : KomgaLibraryApi {

    @Serializable
    private data class LibraryDto(val id: String, val name: String)

    @Serializable
    private data class BookMediaDto(val pagesCount: Int? = null)

    @Serializable
    private data class BookDto(val media: BookMediaDto? = null)

    override suspend fun fetchCbzPageCount(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): Int {
        val client = if (insecureAllowed) httpClient.withInsecureTls() else httpClient
        val response: HttpResponse = client.get("${baseUrl.trimEnd('/')}/api/v1/books/$bookId") {
            header(HttpHeaders.Authorization, token)
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value)
        return response.body<BookDto>().media?.pagesCount ?: 0
    }

    override suspend fun fetchCbzPage(
        baseUrl: String,
        bookId: String,
        pageIndex: Int,
        maxWidth: Int?,
        token: String,
        insecureAllowed: Boolean,
    ): ByteArray {
        val client = if (insecureAllowed) httpClient.withInsecureTls() else httpClient
        val base = "${baseUrl.trimEnd('/')}/api/v1/books/$bookId/pages/${pageIndex + 1}"
        val url = if (maxWidth != null) "$base?width=$maxWidth" else base
        val response: HttpResponse = client.get(url) {
            header(HttpHeaders.Authorization, token)
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value)
        return response.bodyAsBytes()
    }

    override suspend fun getLibraries(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<KomgaLibraryInfo>> = KtorClassifier.classify {
        val client = if (insecureAllowed) httpClient.withInsecureTls() else httpClient
        val response: HttpResponse = client.get("${baseUrl.trimEnd('/')}/api/v1/libraries") {
            header(HttpHeaders.Authorization, token)
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value)
        response.body<List<LibraryDto>>().map { KomgaLibraryInfo(id = it.id, name = it.name) }
    }
}
