package com.riffle.core.domain

/**
 * In-memory holder for a source that has been authenticated but not yet
 * persisted. Carries the auth token AND the user-entered password (held only
 * across the auth→library-picker→commit hop so the password can be persisted
 * alongside the token for prefill on edit). Never persist or log instances of
 * this type.
 */
data class PendingSource(
    val url: SourceUrl,
    val username: String,
    val userId: String,
    val token: String,
    val password: String,
    val insecureConnectionAllowed: Boolean,
    val libraries: List<Library>,
    val serverType: ServerType = ServerType.AUDIOBOOKSHELF,
)
