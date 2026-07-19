package com.riffle.core.models

data class Source(
    val id: String,
    val url: SourceUrl,
    val isActive: Boolean,
    val insecureConnectionAllowed: Boolean,
    val username: String,
    val type: SourceType = SourceType.ABS,
    val serverType: ServerType = ServerType.AUDIOBOOKSHELF,
    /**
     * Stable cross-device user identity on the remote server, generalized per #529 (name kept
     * for storage/backwards-compat reasons). Populated per source kind:
     *  - ABS: `/api/me` `user.id`
     *  - Komga: `/api/v2/users/me` `id`
     *  - Storyteller peer / local / anonymous catalogs: null (the descriptor returns
     *    [SyncNamespace.LocalOnly] and no probe runs).
     *
     * Consumed by [WebSourceDescriptor.syncNamespaceFor] to produce the annotation-sync path
     * namespace. Null on Storyteller sources, on public-catalog sources, on local files, and
     * on legacy rows that haven't been backfilled yet.
     */
    val absUserId: String? = null,
)
