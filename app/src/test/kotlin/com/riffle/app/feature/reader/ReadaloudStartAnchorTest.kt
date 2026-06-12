package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The audiobook→readaloud entry rule (ADR 0031): a newer local audiobook position seeds the readaloud
 * start sentence; otherwise the caller falls back to its resume tiers. Last-update-wins, index-free.
 */
class ReadaloudStartAnchorTest {

    @Test
    fun `a newer local audiobook position seeds the start sentence`() {
        val frag = ReadaloudStartAnchor.fromLocalAudio(
            audioSeconds = 9110.0, audioUpdatedAt = 200L, readingUpdatedAt = 100L,
            fragmentForAudioSeconds = { "ch#s230" },
        )
        assertEquals("ch#s230", frag)
    }

    @Test
    fun `audio not newer than reading does not seed (falls back to tiers) and is not even translated`() {
        var translated = false
        val frag = ReadaloudStartAnchor.fromLocalAudio(
            audioSeconds = 9110.0, audioUpdatedAt = 100L, readingUpdatedAt = 100L, // equal: reading wins ties
            fragmentForAudioSeconds = { translated = true; "x" },
        )
        assertNull(frag)
        assertFalse("must not translate when the audio isn't newer", translated)
    }

    @Test
    fun `older audio does not seed`() {
        val frag = ReadaloudStartAnchor.fromLocalAudio(
            audioSeconds = 9110.0, audioUpdatedAt = 50L, readingUpdatedAt = 100L,
            fragmentForAudioSeconds = { "x" },
        )
        assertNull(frag)
    }

    @Test
    fun `no local audio position does not seed`() {
        var translated = false
        val frag = ReadaloudStartAnchor.fromLocalAudio(
            audioSeconds = null, audioUpdatedAt = 999L, readingUpdatedAt = 0L,
            fragmentForAudioSeconds = { translated = true; "x" },
        )
        assertNull(frag)
        assertFalse(translated)
    }

    @Test
    fun `newer audio that cannot be translated yields null (caller falls back to tiers)`() {
        val frag = ReadaloudStartAnchor.fromLocalAudio(
            audioSeconds = 9110.0, audioUpdatedAt = 200L, readingUpdatedAt = 100L,
            fragmentForAudioSeconds = { null },
        )
        assertNull(frag)
    }
}
