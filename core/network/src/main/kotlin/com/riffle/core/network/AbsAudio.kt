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

