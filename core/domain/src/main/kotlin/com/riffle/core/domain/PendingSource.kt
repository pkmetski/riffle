package com.riffle.core.domain
import com.riffle.core.models.Library
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl

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
    /**
     * The [SourceType] the credentialed installer should stamp on the persisted [Source] row.
     * Defaults to [SourceType.ABS] for backwards compatibility with the pre-ADR-0044 Storyteller
     * + Audiobookshelf path. Komga and any future credentialed source pass their own type so the
     * installer no longer needs to hard-code the column value.
     */
    val sourceType: SourceType = SourceType.ABS,
)
