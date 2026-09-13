package com.riffle.app.feature.source.oreilly

import com.riffle.core.catalog.oreilly.OReillyHttpException
import org.junit.Assert.assertEquals
import org.junit.Test

class OReillyBrowseViewModelTest {

    @Test
    fun `OReillyHttpException does not leak raw URL in error message`() {
        val ex = OReillyHttpException(
            503, "https://learning.oreilly.com/api/v1/search/?query=test",
        )
        val msg = oReillyFriendlyErrorMessage(ex)
        assertEquals("Couldn't reach O'Reilly. Check your connection and try again.", msg)
    }

    @Test
    fun `UnknownHostException surfaces offline message for OReilly`() {
        val ex = java.net.UnknownHostException("Unable to resolve host \"learning.oreilly.com\"")
        val msg = oReillyFriendlyErrorMessage(ex)
        assertEquals("You appear to be offline. Connect to the internet and try again.", msg)
    }
}
