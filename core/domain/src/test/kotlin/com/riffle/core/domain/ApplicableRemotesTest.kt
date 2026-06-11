package com.riffle.core.domain

import com.riffle.core.domain.RemoteKind.ABS_AUDIO
import com.riffle.core.domain.RemoteKind.ABS_EBOOK
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
    fun `a matched book with an ebook and audio target reconciles both ABS peers`() {
        // Two ABS peers only — Storyteller is not a sync peer (ADR 0029). ABS_AUDIO is reconciled
        // both ways: a cross-device listen wins inbound and moves the reader; reading writes it.
        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = true,
            prerequisitesCached = true,
        )

        assertEquals(setOf(ABS_EBOOK, ABS_AUDIO), applicableRemotes(state))
    }

    @Test
    fun `a matched book with no matched audio item syncs ABS ebook only`() {
        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = false,
            prerequisitesCached = true,
        )

        assertEquals(setOf(ABS_EBOOK), applicableRemotes(state))
    }

    @Test
    fun `a split-library match reconciles both ABS items`() {
        // Ebook item and audiobook item are distinct ABS items (separate libraries). They are the
        // same logical book's two media, bridged by the bundle's SMIL — two ABS peers, no Storyteller.
        val state = BookSyncState(
            isMatched = true,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = true,
            prerequisitesCached = true,
        )

        assertEquals(setOf(ABS_EBOOK, ABS_AUDIO), applicableRemotes(state))
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

    @Test
    fun `an unmatched audiobook-only book syncs ABS audio only`() {
        // Opened from the audiobook player; no ebook exists. The single-peer base is the opened
        // medium, so the audiobook reconciles its own ABS audio record — symmetric with an
        // ebook-only book's single ABS ebook peer (ADR 0029).
        val state = BookSyncState(
            isMatched = false,
            openedMedium = OpenedMedium.AUDIO,
            hasAbsEbookTarget = false,
            hasAbsAudioTarget = true,
            prerequisitesCached = false,
        )

        assertEquals(setOf(ABS_AUDIO), applicableRemotes(state))
    }

    @Test
    fun `a matched audiobook opened before prerequisites cache falls back to ABS audio only`() {
        // The audiobook player drives an audio-led canonical; until the Storyteller bundle and
        // cross-EPUB index land it cannot translate to the ebook, so it degrades to the single
        // ABS audio peer, then upgrades to both ABS peers on a later cycle (ADR 0029).
        val state = BookSyncState(
            isMatched = true,
            openedMedium = OpenedMedium.AUDIO,
            hasAbsEbookTarget = true,
            hasAbsAudioTarget = true,
            prerequisitesCached = false,
        )

        assertEquals(setOf(ABS_AUDIO), applicableRemotes(state))
    }
}
