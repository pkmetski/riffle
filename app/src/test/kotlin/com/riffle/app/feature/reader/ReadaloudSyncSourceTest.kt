package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadaloudSyncSourceTest {

    @Test
    fun `while playing, a resolvable audio position is the source of truth`() {
        assertEquals("AUDIO", canonicalForCycle(isPlaying = true, audioCanonicalJson = "AUDIO", pageLocatorJson = "PAGE"))
    }

    @Test
    fun `while playing but the audio position cannot be resolved, fall back to the page`() {
        assertEquals("PAGE", canonicalForCycle(isPlaying = true, audioCanonicalJson = null, pageLocatorJson = "PAGE"))
    }

    @Test
    fun `when not playing, the page is the source even if an audio position exists`() {
        assertEquals("PAGE", canonicalForCycle(isPlaying = false, audioCanonicalJson = "AUDIO", pageLocatorJson = "PAGE"))
    }

    @Test
    fun `null when neither source is available`() {
        assertNull(canonicalForCycle(isPlaying = true, audioCanonicalJson = null, pageLocatorJson = null))
    }
}
