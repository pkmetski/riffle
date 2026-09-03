package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

class KomgaLibraryApiClient(
    private val httpClient: HttpClient,
) : KomgaLibraryApi {

    @Serializable
    private data class LibraryDto(val id: String, val name: String)

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
