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
            confirmedAbsLinkCount = 0,
            prerequisitesCached = false,
        )

        assertEquals(setOf(ABS_EBOOK), applicableRemotes(state))
    }

    @Test
    fun `a matched book with one ABS link and cached prerequisites runs three-peer`() {
        val state = BookSyncState(isMatched = true, confirmedAbsLinkCount = 1, prerequisitesCached = true)

        assertEquals(setOf(ABS_EBOOK, ABS_AUDIO, STORYTELLER), applicableRemotes(state))
    }

    @Test
    fun `the multi-link guard excludes Storyteller and degrades to a two-peer ABS cycle`() {
        val state = BookSyncState(
            isMatched = true,
            confirmedAbsLinkCount = 2,
            prerequisitesCached = true,
        )

        assertEquals(setOf(ABS_EBOOK, ABS_AUDIO), applicableRemotes(state))
    }

    @Test
    fun `a matched book without cached prerequisites falls back to ABS ebook only`() {
        val state = BookSyncState(isMatched = true, confirmedAbsLinkCount = 1, prerequisitesCached = false)

        assertEquals(setOf(ABS_EBOOK), applicableRemotes(state))
    }
}
