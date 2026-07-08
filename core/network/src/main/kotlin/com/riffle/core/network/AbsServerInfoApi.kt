package com.riffle.core.network

data class NetworkListeningStats(
    val totalTimeSec: Double,
)

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

    /**
     * `GET /api/me/listening-stats`. Returns aggregate listening time in seconds. Item counts
     * (in-progress / finished) are computed by the Catalog from `/api/me` mediaProgress and are
     * not part of this payload.
     */
    suspend fun getListeningStats(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkListeningStats> = throw UnsupportedOperationException("getListeningStats not implemented")
}
