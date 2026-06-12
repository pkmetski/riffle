package com.riffle.app.feature.reader

/**
 * The audiobook→readaloud entry rule of ADR 0031: when readaloud starts, if the **local** audiobook
 * position is newer than the local reading position (e.g. an offline listen), readaloud should begin
 * at that listen position's sentence — deduced bundle-SMIL-only, index-free. Last-update-wins across
 * the two local stores; `null` means "audio isn't newer / can't be placed," so the caller falls back
 * to its resume tiers. Pure and side-effect-free.
 */
object ReadaloudStartAnchor {

    inline fun fromLocalAudio(
        audioSeconds: Double?,
        audioUpdatedAt: Long,
        readingUpdatedAt: Long,
        fragmentForAudioSeconds: (Double) -> String?,
    ): String? =
        if (audioSeconds != null && audioUpdatedAt > readingUpdatedAt) fragmentForAudioSeconds(audioSeconds) else null
}
