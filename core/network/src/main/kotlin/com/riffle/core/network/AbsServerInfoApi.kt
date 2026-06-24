package com.riffle.core.network

interface AbsServerInfoApi {
    suspend fun getServerInfo(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): String?

    /**
     * Fetch the currently-authenticated user's stable ABS id (`/api/me` → `user.id`). Used to
     * backfill annotation-sync namespaces on legacy server rows that pre-date the
     * `servers.absUserId` column. Returns null on any network/parse failure.
     */
    suspend fun getCurrentUserId(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): String?
}
