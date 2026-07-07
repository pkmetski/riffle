package com.riffle.core.domain

data class Source(
    val id: String,
    val url: SourceUrl,
    val isActive: Boolean,
    val insecureConnectionAllowed: Boolean,
    val username: String,
    val type: SourceType = SourceType.ABS,
    val serverType: ServerType = ServerType.AUDIOBOOKSHELF,
    /**
     * ABS-side stable user identity (the `/api/me` `user.id`). Used by annotation sync as the
     * cross-device path namespace — see [com.riffle.core.domain.AnnotationSyncTarget]. Null on
     * Storyteller servers and on legacy rows that haven't been backfilled yet.
     */
    val absUserId: String? = null,
)
