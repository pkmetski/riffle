package com.riffle.core.network

fun interface AbsApi {
    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkLoginResult
}
