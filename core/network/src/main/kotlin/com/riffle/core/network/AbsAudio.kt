package com.riffle.core.network

/** One streamable ABS audiobook track: its file inode and playable duration (ADR 0028). */
data class NetworkAbsAudioTrack(
    val ino: String,
    val index: Int,
    val durationSec: Double,
)

/** Builds ABS audio stream URLs. The endpoint serves the file with HTTP Range support. */
object AbsAudioUrl {
    fun track(baseUrl: String, itemId: String, ino: String): String =
        "${baseUrl.trimEnd('/')}/api/items/$itemId/file/$ino"
}

/**
 * Builds ABS item-cover URLs. Cover requests are unauthenticated at the ABS layer; the optional
 * `?t=` query param busts client image caches when the item is re-covered upstream.
 */
object AbsCoverUrl {
    fun of(baseUrl: String, itemId: String, updatedAt: Long?): String =
        "${baseUrl.trimEnd('/')}/api/items/$itemId/cover" + (updatedAt?.let { "?t=$it" } ?: "")
}

