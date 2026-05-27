package com.riffle.app.feature.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.shared.publication.Locator

// EpubReaderViewModel is an AndroidViewModel with Readium dependencies that cannot be
// instantiated in JVM unit tests. These tests verify the StateFlow pattern used by
// showFootnotePopup() and dismissFootnotePopup() in isolation.
@OptIn(ExperimentalCoroutinesApi::class)
class EpubReaderViewModelFootnoteTest {

    @Test
    fun `footnote popup flow emits state when set`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableStateFlow<FootnotePopupState?>(null)
        val emissions = mutableListOf<FootnotePopupState?>()
        backgroundScope.launch { flow.collect { emissions.add(it) } }

        flow.value = FootnotePopupState("Heraclitus of Ephesus (c. 535 BC)")

        assertEquals(2, emissions.size)
        assertEquals("Heraclitus of Ephesus (c. 535 BC)", emissions[1]?.content)
    }

    @Test
    fun `footnote popup flow emits null after dismiss`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableStateFlow<FootnotePopupState?>(null)
        val emissions = mutableListOf<FootnotePopupState?>()
        backgroundScope.launch { flow.collect { emissions.add(it) } }

        flow.value = FootnotePopupState("Some footnote")
        flow.value = null

        assertEquals(3, emissions.size)
        assertNull(emissions[0])
        assertEquals("Some footnote", emissions[1]?.content)
        assertNull(emissions[2])
    }

    @Test
    fun `showing a second footnote replaces the first`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableStateFlow<FootnotePopupState?>(null)
        val emissions = mutableListOf<FootnotePopupState?>()
        backgroundScope.launch { flow.collect { emissions.add(it) } }

        flow.value = FootnotePopupState("First footnote")
        flow.value = FootnotePopupState("Second footnote")

        assertEquals(3, emissions.size)
        assertEquals("First footnote", emissions[1]?.content)
        assertEquals("Second footnote", emissions[2]?.content)
    }

    @Test
    fun `blank lastPosition does not crash Locator parsing`() {
        val blanks = listOf("", " ", null)
        for (input in blanks) {
            val locator = input?.takeIf { it.isNotBlank() }?.let {
                try { Locator.fromJSON(JSONObject(it)) } catch (_: Exception) { null }
            }
            assertNull("Expected null for input='$input'", locator)
        }
    }

    @Test
    fun `malformed lastPosition does not crash Locator parsing`() {
        val malformed = listOf("{", "not json", "{\"href\":}")
        for (input in malformed) {
            val locator = input.takeIf { it.isNotBlank() }?.let {
                try { Locator.fromJSON(JSONObject(it)) } catch (_: Exception) { null }
            }
            assertNull("Expected null for input='$input'", locator)
        }
    }

    @Test
    fun `FootnotePopupState equality is structural`() {
        val a = FootnotePopupState("text")
        val b = FootnotePopupState("text")
        val c = FootnotePopupState("other")
        assertEquals(a, b)
        assert(a != c)
    }
}
