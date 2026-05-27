package com.riffle.app.feature.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

        // Simulate showFootnotePopup(content, tapX, tapY)
        flow.value = FootnotePopupState("Heraclitus of Ephesus (c. 535 BC)", 120f, 340f)

        assertEquals(2, emissions.size) // initial null + new value
        val state = emissions[1]
        assertEquals("Heraclitus of Ephesus (c. 535 BC)", state?.content)
        assertEquals(120f, state?.tapX)
        assertEquals(340f, state?.tapY)
    }

    @Test
    fun `footnote popup flow emits null after dismiss`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableStateFlow<FootnotePopupState?>(null)
        val emissions = mutableListOf<FootnotePopupState?>()
        backgroundScope.launch { flow.collect { emissions.add(it) } }

        // Simulate showFootnotePopup then dismissFootnotePopup
        flow.value = FootnotePopupState("Some footnote", 50f, 100f)
        flow.value = null

        assertEquals(3, emissions.size) // null → state → null
        assertNull(emissions[0])
        assertEquals("Some footnote", emissions[1]?.content)
        assertNull(emissions[2])
    }

    @Test
    fun `showing a second footnote replaces the first`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableStateFlow<FootnotePopupState?>(null)
        val emissions = mutableListOf<FootnotePopupState?>()
        backgroundScope.launch { flow.collect { emissions.add(it) } }

        flow.value = FootnotePopupState("First footnote", 10f, 20f)
        flow.value = FootnotePopupState("Second footnote", 30f, 40f)

        assertEquals(3, emissions.size)
        assertEquals("First footnote", emissions[1]?.content)
        assertEquals("Second footnote", emissions[2]?.content)
        assertEquals(30f, emissions[2]?.tapX)
        assertEquals(40f, emissions[2]?.tapY)
    }

    @Test
    fun `FootnotePopupState equality is structural`() {
        val a = FootnotePopupState("text", 1f, 2f)
        val b = FootnotePopupState("text", 1f, 2f)
        val c = FootnotePopupState("other", 1f, 2f)
        assertEquals(a, b)
        assert(a != c)
    }
}
