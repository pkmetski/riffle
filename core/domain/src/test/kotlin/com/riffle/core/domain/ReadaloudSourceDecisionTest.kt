package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chooses, per book, where Readaloud audio comes from (ADR 0028). Streaming is taken only when the
 * audiobook is linked AND its identity is verified AND the (dark-launch) switch is on — so with the
 * switch off, every book falls back to the bundle and runtime behaviour is unchanged.
 */
class ReadaloudSourceDecisionTest {

    @Test
    fun `streams when linked, verified and enabled`() {
        assertEquals(
            ReadaloudAudioSource.STREAM,
            ReadaloudSourceDecision.decide(audiobookLinked = true, identityVerified = true, streamingEnabled = true),
        )
    }

    @Test
    fun `the dark switch off forces the bundle even when otherwise eligible`() {
        assertEquals(
            ReadaloudAudioSource.BUNDLE,
            ReadaloudSourceDecision.decide(audiobookLinked = true, identityVerified = true, streamingEnabled = false),
        )
    }

    @Test
    fun `no audiobook link falls back to the bundle`() {
        assertEquals(
            ReadaloudAudioSource.BUNDLE,
            ReadaloudSourceDecision.decide(audiobookLinked = false, identityVerified = true, streamingEnabled = true),
        )
    }

    @Test
    fun `failed identity falls back to the bundle`() {
        assertEquals(
            ReadaloudAudioSource.BUNDLE,
            ReadaloudSourceDecision.decide(audiobookLinked = true, identityVerified = false, streamingEnabled = true),
        )
    }
}
