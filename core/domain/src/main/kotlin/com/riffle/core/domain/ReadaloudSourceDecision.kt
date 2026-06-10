package com.riffle.core.domain

/** Where a book's Readaloud audio is sourced from (ADR 0028). */
enum class ReadaloudAudioSource { STREAM, BUNDLE }

/**
 * The per-book source choice (ADR 0028). Streaming is taken only when the ABS audiobook is linked
 * AND its identity is verified ([AudiobookIdentity]); everything else falls back to the bundle, so a
 * name-matched-but-different audiobook never silently mis-syncs. This per-book gate — not a global
 * switch — is the safety mechanism.
 */
object ReadaloudSourceDecision {
    fun decide(
        audiobookLinked: Boolean,
        identityVerified: Boolean,
    ): ReadaloudAudioSource =
        if (audiobookLinked && identityVerified) {
            ReadaloudAudioSource.STREAM
        } else {
            ReadaloudAudioSource.BUNDLE
        }
}
