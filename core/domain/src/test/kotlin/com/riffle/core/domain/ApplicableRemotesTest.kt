package com.riffle.core.domain

import com.riffle.core.domain.RemoteKind.ABS_AUDIO
import com.riffle.core.domain.RemoteKind.ABS_EBOOK
import com.riffle.core.domain.RemoteKind.STORYTELLER
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicableRemotesTest {

    @Test
    fun `an unmatched book opened from the ABS side syncs ABS ebook only`() {
        val state = BookSyncState(
            isMatched = false,
            confirmedAbsLinkCount = 0,
            prerequisitesCached = false,
            openedSide = OpenedSide.ABS,
        )

        assertEquals(setOf(ABS_EBOOK), applicableRemotes(state))
    }

    @Test
    fun `a Storyteller-only book opened from the Readaloud side syncs Storyteller only`() {
        val state = BookSyncState(
            isMatched = false,
            confirmedAbsLinkCount = 0,
            prerequisitesCached = false,
            openedSide = OpenedSide.READALOUD,
        )

        assertEquals(setOf(STORYTELLER), applicableRemotes(state))
    }

    @Test
    fun `a matched book with one ABS link and cached prerequisites runs three-peer from either side`() {
        val absSide = BookSyncState(true, confirmedAbsLinkCount = 1, prerequisitesCached = true, openedSide = OpenedSide.ABS)
        val readaloudSide = absSide.copy(openedSide = OpenedSide.READALOUD)

        val expected = setOf(ABS_EBOOK, ABS_AUDIO, STORYTELLER)
        assertEquals(expected, applicableRemotes(absSide))
        assertEquals(expected, applicableRemotes(readaloudSide))
    }

    @Test
    fun `the multi-link guard excludes Storyteller and degrades to a two-peer ABS cycle`() {
        val state = BookSyncState(
            isMatched = true,
            confirmedAbsLinkCount = 2,
            prerequisitesCached = true,
            openedSide = OpenedSide.READALOUD,
        )

        assertEquals(setOf(ABS_EBOOK, ABS_AUDIO), applicableRemotes(state))
    }

    @Test
    fun `a matched book without cached prerequisites falls back to its side's single peer`() {
        val absSide = BookSyncState(true, confirmedAbsLinkCount = 1, prerequisitesCached = false, openedSide = OpenedSide.ABS)
        val readaloudSide = absSide.copy(openedSide = OpenedSide.READALOUD)

        assertEquals(setOf(ABS_EBOOK), applicableRemotes(absSide))
        assertEquals(setOf(STORYTELLER), applicableRemotes(readaloudSide))
    }
}
