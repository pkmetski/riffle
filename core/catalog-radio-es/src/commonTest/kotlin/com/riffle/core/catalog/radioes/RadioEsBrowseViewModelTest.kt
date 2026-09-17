package com.riffle.core.catalog.radioes

import kotlin.test.Test
import kotlin.test.assertEquals

class RadioEsBrowseViewModelTest {

    @Test
    fun `RadioEsHttpException does not leak raw URL in error message`() {
        val ex = RadioEsHttpException(
            503, "https://api.radio.es/info/v2/search/stationsandshows?query=test", "Service Unavailable",
        )
        val msg = radioEsFriendlyErrorMessage(ex)
        assertEquals("Couldn't reach radio.es. Check your connection and try again.", msg)
    }

    @Test
    fun `UnknownHostException surfaces offline message`() {
        val ex = FakeUnknownHostException("Unable to resolve host")
        val msg = radioEsFriendlyErrorMessage(ex)
        assertEquals("You appear to be offline. Connect to the internet and try again.", msg)
    }

    @Test
    fun `IOException surfaces connection error message`() {
        val ex = FakeIOException("Connection refused")
        val msg = radioEsFriendlyErrorMessage(ex)
        assertEquals("Couldn't reach radio.es. Check your connection and try again.", msg)
    }
}

/** Simulates java.net.UnknownHostException (simpleName contains "UnknownHostException"). */
private class FakeUnknownHostException(msg: String) : Exception(msg)

/** Simulates java.io.IOException (simpleName ends with "IOException"). */
private class FakeIOException(msg: String) : Exception(msg)
