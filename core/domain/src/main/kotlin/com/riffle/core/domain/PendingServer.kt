package com.riffle.core.domain

/**
 * In-memory holder for a server that has been authenticated but not yet
 * persisted. Carries the auth token, so never persist or log instances of
 * this type.
 */
data class PendingServer(
    val url: ServerUrl,
    val displayName: String,
    val username: String,
    val userId: String,
    val token: String,
    val insecureConnectionAllowed: Boolean,
    val libraries: List<Library>,
    val serverType: ServerType = ServerType.AUDIOBOOKSHELF,
)
