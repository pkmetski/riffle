package com.riffle.app.feature.source.radioes

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioEsBrowseViewModelTest {

    @Test
    fun `RadioEsHttpException does not leak raw URL in error message`() {
        val ex = com.riffle.core.catalog.radioes.RadioEsHttpException(
            503, "https://api.radio.es/info/v2/search/stationsandshows?query=test", "Service Unavailable",
        )
        val msg = radioEsFriendlyErrorMessage(ex)
        assertEquals("Couldn't reach radio.es. Check your connection and try again.", msg)
    }

    @Test
    fun `UnknownHostException surfaces offline message for RadioEs`() {
        val ex = java.net.UnknownHostException("Unable to resolve host \"api.radio.es\"")
        val msg = radioEsFriendlyErrorMessage(ex)
        assertEquals("You appear to be offline. Connect to the internet and try again.", msg)
    }
}
