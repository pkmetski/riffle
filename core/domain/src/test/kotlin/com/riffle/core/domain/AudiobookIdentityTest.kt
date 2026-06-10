package com.riffle.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity check that gates the streaming path (ADR 0028): the ABS audiobook must be the
 * same recording Storyteller aligned against, compared via the *ingested-source* fingerprint
 * (file size + total duration + per-track durations) that Storyteller's /api/v2 record exposes.
 */
class AudiobookIdentityTest {

    private val martian = AudiobookFingerprint(
        fileSizeBytes = 313_869_927,
        durationSec = 39_214.464,
        trackDurationsSec = listOf(39_214.464),
    )

    @Test
    fun `identical fingerprints match`() {
        assertTrue(AudiobookIdentity.matches(martian, martian))
    }

    @Test
    fun `sub-second jitter in durations still matches`() {
        val jittered = martian.copy(
            durationSec = 39_214.9,
            trackDurationsSec = listOf(39_214.9),
        )
        assertTrue(AudiobookIdentity.matches(martian, jittered))
    }

    @Test
    fun `a different total duration is not a match`() {
        val shorter = martian.copy(durationSec = 38_000.0, trackDurationsSec = listOf(38_000.0))
        assertFalse(AudiobookIdentity.matches(martian, shorter))
    }

    @Test
    fun `a different track split is not a match even when totals agree`() {
        // Same ~total, but ABS is one file while the other side reports two tracks.
        val twoTracks = martian.copy(trackDurationsSec = listOf(19_607.0, 19_607.464))
        assertFalse(AudiobookIdentity.matches(martian, twoTracks))
    }

    @Test
    fun `a different file size is not a match`() {
        val reencoded = martian.copy(fileSizeBytes = 90_000_000)
        assertFalse(AudiobookIdentity.matches(martian, reencoded))
    }

    @Test
    fun `multi-track durations match pairwise within tolerance`() {
        val phm = AudiobookFingerprint(
            fileSizeBytes = 485_000_000,
            durationSec = 8_356.0,
            trackDurationsSec = listOf(2_204.0, 1_721.0, 2_241.0, 2_190.0),
        )
        val phmJitter = phm.copy(
            trackDurationsSec = listOf(2_204.3, 1_720.8, 2_241.1, 2_189.9),
        )
        assertTrue(AudiobookIdentity.matches(phm, phmJitter))
    }
}
