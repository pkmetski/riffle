package com.riffle.core.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class LogChannelTest {
    @Test
    fun tagsMatchTheDebugRecipes() {
        // Stable wire-format: these tag strings are referenced by adb logcat recipes in AGENTS.md
        // and the debug skills. Changing them silently breaks team debugging.
        assertEquals("RIFFLE_RA", LogChannel.Readaloud.tag)
        assertEquals("RIFFLE_AB", LogChannel.Audiobook.tag)
        assertEquals("RIFFLE_HANDOFF", LogChannel.Handoff.tag)
    }
}
