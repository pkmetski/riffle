package com.riffle.core.domain

import com.riffle.core.domain.RemoteKind.ABS_AUDIO
import com.riffle.core.domain.RemoteKind.ABS_EBOOK
import com.riffle.core.domain.RemoteKind.STORYTELLER
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicableRemotesTest {

    @Test
    fun `an unmatched book syncs ABS ebook only`() {
        val state = BookSyncState(
            isMatched = false,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = false,
            prerequisitesCached = false,
        )

        assertEquals(setOf(ABS_EBOOK), applicableRemotes(state))
    }

    @Test
    fun `a matched book with an ebook and audio target reconciles all three peers`() {
        // ABS_AUDIO is in the set, reconciled inbound-only: the cycle reads it so a cross-device
        // listen wins and moves the reader, but never writes it (outbound is the push).
        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = true,
            prerequisitesCached = true,
        )

        assertEquals(setOf(ABS_EBOOK, ABS_AUDIO, STORYTELLER), applicableRemotes(state))
    }

    @Test
    fun `a matched book with no matched audio item omits the ABS audio remote`() {
        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = false,
            prerequisitesCached = true,
        )

        assertEquals(setOf(ABS_EBOOK, STORYTELLER), applicableRemotes(state))
    }

    @Test
    fun `a split-library match reconciles Storyteller alongside both ABS items`() {
        // Ebook item and audiobook item are distinct ABS items (separate libraries). They are the
        // same logical book's two media, so all three peers are reconciled (the audiobook inbound).
        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = true,
            prerequisitesCached = true,
        )

        assertEquals(setOf(ABS_EBOOK, ABS_AUDIO, STORYTELLER), applicableRemotes(state))
    }

    @Test
    fun `a matched book without cached prerequisites falls back to ABS ebook only`() {
        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = true,
            prerequisitesCached = false,
        )

        assertEquals(setOf(ABS_EBOOK), applicableRemotes(state))
    }
}
