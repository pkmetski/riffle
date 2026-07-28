package com.riffle.core.network

data class NetworkLoginUser(
    val userId: String,
    val token: String,
    val username: String,
)

fun interface AbsApi {
    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkLoginUser>
}
