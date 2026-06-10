package com.riffle.core.domain

/** Where a book's Readaloud audio is sourced from (ADR 0028). */
enum class ReadaloudAudioSource { STREAM, BUNDLE }

/**
 * The per-book source choice and the dark-launch kill switch in one place. Streaming requires all
 * three of: a linked ABS audiobook, a verified identity ([AudiobookIdentity]), and the streaming
 * switch on. `streamingEnabled` defaults off in production, so until it is flipped every book —
 * including those that *could* stream — resolves to [ReadaloudAudioSource.BUNDLE] and nothing about
 * the existing path changes.
 */
object ReadaloudSourceDecision {
    fun decide(
        audiobookLinked: Boolean,
        identityVerified: Boolean,
        streamingEnabled: Boolean,
    ): ReadaloudAudioSource =
        if (audiobookLinked && identityVerified && streamingEnabled) {
            ReadaloudAudioSource.STREAM
        } else {
            ReadaloudAudioSource.BUNDLE
        }
}
