package com.riffle.core.domain

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
    fun `a matched book reconciles the ebook and Storyteller — never the audiobook`() {
        // The audiobook is push-only: it must never be a reconciled remote, or a divergent audio
        // clock would win the cycle and drive the ebook to the audio position (data loss).
        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = true,
            prerequisitesCached = true,
        )

        assertEquals(setOf(ABS_EBOOK, STORYTELLER), applicableRemotes(state))
    }

    @Test
    fun `a matched book with no matched audio item still reconciles ebook and Storyteller`() {
        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = false,
            prerequisitesCached = true,
        )

        assertEquals(setOf(ABS_EBOOK, STORYTELLER), applicableRemotes(state))
    }

    @Test
    fun `a split-library match reconciles the ebook and Storyteller while the audiobook stays push-only`() {
        // Ebook item and audiobook item are distinct ABS items (separate libraries). The audiobook is
        // still push-only — only the ebook and Storyteller are reconciled.
        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = true,
            prerequisitesCached = true,
        )

        assertEquals(setOf(ABS_EBOOK, STORYTELLER), applicableRemotes(state))
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
