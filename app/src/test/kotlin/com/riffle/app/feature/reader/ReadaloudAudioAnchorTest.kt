package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audiobook-anchor rule of ADR 0031: the narrated **fragment** is the pivot; the **page**
 * canonical is a fallback only for silent reading, never during an active readaloud (the page-top
 * race that synced the audiobook ~a minute early). Pure-unit coverage of every branch.
 */
class ReadaloudAudioAnchorTest {

    @Test
    fun `narrated fragment maps to its exact sentence second`() {
        val s = ReadaloudAudioAnchor.audiobookSeconds(
            activeFragment = "ch#s1", readaloudOpen = true,
            fragmentSeconds = { 9160.0 }, pageSeconds = { 9110.0 },
        )
        assertEquals(9160.0, s!!, 0.0001)
    }

    @Test
    fun `an unresolvable fragment writes nothing — it does NOT fall back to the page`() {
        // This is the core anti-regression: during readaloud a missing fragment must not page-top.
        val s = ReadaloudAudioAnchor.audiobookSeconds(
            activeFragment = "ch#missing", readaloudOpen = true,
            fragmentSeconds = { null }, pageSeconds = { 9110.0 },
        )
        assertNull(s)
    }

    @Test
    fun `active readaloud with no fragment yet (race) skips — never writes the page-top`() {
        var pageConsulted = false
        val s = ReadaloudAudioAnchor.audiobookSeconds(
            activeFragment = null, readaloudOpen = true,
            fragmentSeconds = { 0.0 }, pageSeconds = { pageConsulted = true; 9110.0 },
        )
        assertNull("the transient null-fragment race must not write", s)
        assertFalse("the page coordinate must NOT even be evaluated during active readaloud", pageConsulted)
    }

    @Test
    fun `silent reading (no readaloud) anchors on the page-top sentence`() {
        val s = ReadaloudAudioAnchor.audiobookSeconds(
            activeFragment = null, readaloudOpen = false,
            fragmentSeconds = { 9160.0 }, pageSeconds = { 9110.0 },
        )
        assertEquals(9110.0, s!!, 0.0001)
    }

    @Test
    fun `silent reading with no resolvable page writes nothing`() {
        val s = ReadaloudAudioAnchor.audiobookSeconds(
            activeFragment = null, readaloudOpen = false,
            fragmentSeconds = { 9160.0 }, pageSeconds = { null },
        )
        assertNull(s)
    }

    @Test
    fun `a present fragment never consults the page (no page round-trip while narrating)`() {
        var pageConsulted = false
        ReadaloudAudioAnchor.audiobookSeconds(
            activeFragment = "ch#s1", readaloudOpen = true,
            fragmentSeconds = { 9160.0 }, pageSeconds = { pageConsulted = true; 9110.0 },
        )
        assertFalse(pageConsulted)
    }

    @Test
    fun `the fragment source is queried with the active fragment ref`() {
        var queried: String? = null
        ReadaloudAudioAnchor.audiobookSeconds(
            activeFragment = "ch#s42", readaloudOpen = true,
            fragmentSeconds = { queried = it; 1.0 }, pageSeconds = { null },
        )
        assertEquals("ch#s42", queried)
    }
}
