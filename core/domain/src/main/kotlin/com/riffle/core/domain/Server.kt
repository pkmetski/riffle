package com.riffle.core.domain

data class Server(
    val id: String,
    val url: ServerUrl,
    val displayName: String,
    val isActive: Boolean,
    val insecureConnectionAllowed: Boolean,
    val username: String,
)
