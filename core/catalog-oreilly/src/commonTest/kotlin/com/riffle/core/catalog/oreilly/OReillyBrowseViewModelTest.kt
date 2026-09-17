package com.riffle.core.catalog.oreilly

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `UnknownHostException surfaces offline message`() {
        val ex = FakeUnknownHostException("Unable to resolve host")
        val msg = oReillyFriendlyErrorMessage(ex)
        assertEquals("You appear to be offline. Connect to the internet and try again.", msg)
    }

    @Test
    fun `IOException surfaces connection error message`() {
        val ex = FakeIOException("Connection refused")
        val msg = oReillyFriendlyErrorMessage(ex)
        assertEquals("Couldn't reach O'Reilly. Check your connection and try again.", msg)
    }
}

/** Simulates java.net.UnknownHostException (simpleName == "UnknownHostException"). */
private class FakeUnknownHostException(msg: String) : Exception(msg)

/** Simulates java.io.IOException (simpleName ends with "IOException"). */
private class FakeIOException(msg: String) : Exception(msg)
