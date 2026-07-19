package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import com.riffle.core.models.AudiobookIdentityResult

/** The matches-screen streaming status derivation (ADR 0028). */
class ConfirmedReadaloudStatusTest {

    private fun target(hasAudio: Boolean, verdict: AudiobookIdentityResult) =
        ConfirmedReadaloud.ConfirmedTarget(
            "s", "i", "t", "lib", hasEbook = !hasAudio, hasAudio = hasAudio, identityResult = verdict,
        )

    private fun confirmed(vararg targets: ConfirmedReadaloud.ConfirmedTarget) =
        ConfirmedReadaloud("st", "b", "Title", targets.toList())

    @Test
    fun `linked audiobook, verified → STREAMING`() {
        val c = confirmed(
            target(hasAudio = false, AudiobookIdentityResult.UNKNOWN), // the ebook target
            target(hasAudio = true, AudiobookIdentityResult.VERIFIED),
        )
        assertEquals(ConfirmedReadaloud.StreamingStatus.STREAMING, c.streamingStatus)
    }

    @Test
    fun `no audiobook target → DOWNLOAD_ONLY_NO_AUDIOBOOK`() {
        val c = confirmed(target(hasAudio = false, AudiobookIdentityResult.UNKNOWN))
        assertEquals(ConfirmedReadaloud.StreamingStatus.DOWNLOAD_ONLY_NO_AUDIOBOOK, c.streamingStatus)
    }

    @Test
    fun `audiobook present but mismatched → DOWNLOAD_ONLY_MISMATCH`() {
        val c = confirmed(target(hasAudio = true, AudiobookIdentityResult.MISMATCH))
        assertEquals(ConfirmedReadaloud.StreamingStatus.DOWNLOAD_ONLY_MISMATCH, c.streamingStatus)
    }

    @Test
    fun `audiobook present but not yet checked → UNKNOWN`() {
        val c = confirmed(target(hasAudio = true, AudiobookIdentityResult.UNKNOWN))
        assertEquals(ConfirmedReadaloud.StreamingStatus.UNKNOWN, c.streamingStatus)
    }
}
