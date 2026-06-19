package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chooses, per book, where Readaloud audio comes from (ADR 0028). Streaming requires a linked ABS
 * audiobook whose identity is verified; anything else falls back to the (always-correct) bundle.
 */
class ReadaloudSourceDecisionTest {

    @Test
    fun `streams when the audiobook is linked and verified`() {
        assertEquals(
            ReadaloudAudioSource.STREAM,
            ReadaloudSourceDecision.decide(audiobookLinked = true, identityVerified = true),
        )
    }

    @Test
    fun `no audiobook link falls back to the bundle`() {
        assertEquals(
            ReadaloudAudioSource.BUNDLE,
            ReadaloudSourceDecision.decide(audiobookLinked = false, identityVerified = true),
        )
    }

    @Test
    fun `failed identity falls back to the bundle`() {
        assertEquals(
            ReadaloudAudioSource.BUNDLE,
            ReadaloudSourceDecision.decide(audiobookLinked = true, identityVerified = false),
        )
    }
}
