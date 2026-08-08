package com.riffle.core.network

interface KomgaServerInfoApi {
    suspend fun getServerVersion(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): String?
}
