package com.riffle.core.network

data class KomgaLibraryInfo(val id: String, val name: String)

interface KomgaLibraryApi : KomgaCbzApi {
    suspend fun getLibraries(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<KomgaLibraryInfo>>
}
