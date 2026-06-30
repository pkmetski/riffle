package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.AudiobookFingerprint
import com.riffle.core.domain.AudiobookIdentityResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turns the two fetched fingerprints into a verdict (ADR 0028). Extracted from the credential/
 * fetch glue so the decision logic is testable without faking the full network interfaces.
 */
class AudiobookIdentityResolverTest {

    private val martian = AudiobookFingerprint(313_869_927, 39_214.464, listOf(39_214.464))

    private fun ok(fp: AudiobookFingerprint): NetworkResult<AudiobookFingerprint?> = NetworkResult.Success(fp)
    private val noAudiobook: NetworkResult<AudiobookFingerprint?> = NetworkResult.Success(null)

    @Test
    fun `matching fingerprints verify`() {
        assertEquals(AudiobookIdentityResult.VERIFIED, AudiobookIdentityResolver.resolve(ok(martian), ok(martian)))
    }

    @Test
    fun `differing fingerprints mismatch`() {
        val other = martian.copy(fileSizeBytes = 90_000_000)
        assertEquals(AudiobookIdentityResult.MISMATCH, AudiobookIdentityResolver.resolve(ok(martian), ok(other)))
    }

    @Test
    fun `no audiobook on either side resolves to NO_AUDIOBOOK`() {
        assertEquals(
            AudiobookIdentityResult.NO_AUDIOBOOK,
            AudiobookIdentityResolver.resolve(noAudiobook, ok(martian)),
        )
    }

    @Test
    fun `a fetch error resolves to UNKNOWN, never a false verify`() {
        assertEquals(
            AudiobookIdentityResult.UNKNOWN,
            AudiobookIdentityResolver.resolve(ok(martian), NetworkResult.Offline(RuntimeException())),
        )
    }
}
