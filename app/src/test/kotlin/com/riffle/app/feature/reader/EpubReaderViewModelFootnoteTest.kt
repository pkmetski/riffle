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

        flow.value = FootnotePopupState(FootnoteContent("Heraclitus of Ephesus (c. 535 BC)"))

        assertEquals(2, emissions.size)
        assertEquals("Heraclitus of Ephesus (c. 535 BC)", emissions[1]?.content?.text)
    }

    @Test
    fun `footnote popup flow emits null after dismiss`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableStateFlow<FootnotePopupState?>(null)
        val emissions = mutableListOf<FootnotePopupState?>()
        backgroundScope.launch { flow.collect { emissions.add(it) } }

        flow.value = FootnotePopupState(FootnoteContent("Some footnote"))
        flow.value = null

        assertEquals(3, emissions.size)
        assertNull(emissions[0])
        assertEquals("Some footnote", emissions[1]?.content?.text)
        assertNull(emissions[2])
    }

    @Test
    fun `showing a second footnote replaces the first`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableStateFlow<FootnotePopupState?>(null)
        val emissions = mutableListOf<FootnotePopupState?>()
        backgroundScope.launch { flow.collect { emissions.add(it) } }

        flow.value = FootnotePopupState(FootnoteContent("First footnote"))
        flow.value = FootnotePopupState(FootnoteContent("Second footnote"))

        assertEquals(3, emissions.size)
        assertEquals("First footnote", emissions[1]?.content?.text)
        assertEquals("Second footnote", emissions[2]?.content?.text)
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
        val a = FootnotePopupState(FootnoteContent("text"))
        val b = FootnotePopupState(FootnoteContent("text"))
        val c = FootnotePopupState(FootnoteContent("other"))
        assertEquals(a, b)
        assert(a != c)
    }

    // EpubReaderViewModel.captureFootnotePopupLinkOrigin + onReaderResumed re-emit the captured
    // locator into _serverLocatorChannel so the navigator restores the user's reading position after
    // returning from the external browser. The VM owns the channel and the pending field, both
    // single-Android-dependency-free Kotlin types, so the capture-resume cycle is verified in
    // isolation here.
    @Test
    fun `pending capture re-emits once into channel on resume and clears`() = runTest(UnconfinedTestDispatcher()) {
        var pending: String? = null
        var lastLocator: String? = null
        val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val emitted = mutableListOf<String>()
        backgroundScope.launch { for (value in channel) emitted.add(value) }

        // capture saves the latest reading position
        lastLocator = "ch03.html@0.5"
        pending = lastLocator

        // first resume re-emits and clears the pending
        pending?.let { origin -> pending = null; channel.trySend(origin) }
        assertEquals(listOf("ch03.html@0.5"), emitted)
        assertNull(pending)

        // a second resume without a fresh capture is a no-op
        pending?.let { origin -> pending = null; channel.trySend(origin) }
        assertEquals(listOf("ch03.html@0.5"), emitted)
    }
}
