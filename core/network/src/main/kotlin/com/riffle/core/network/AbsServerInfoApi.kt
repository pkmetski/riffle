package com.riffle.core.network

interface AbsServerInfoApi {
    suspend fun getServerInfo(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): String?
}
