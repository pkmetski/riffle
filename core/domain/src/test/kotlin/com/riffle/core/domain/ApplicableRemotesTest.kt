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
    fun `a matched book with both an ebook and an audio target runs all three peers`() {
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
    fun `a split-library match still syncs Storyteller alongside both ABS items`() {
        // Ebook item and audiobook item are distinct ABS items (separate libraries). They are the
        // same logical book's two media, not two users sharing a position, so Storyteller stays in.
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
