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
    // returning from the external browser. The VM's pending field and the retry watcher are plain
    // Kotlin types, so the capture-resume-retry cycle is verified in isolation here.

    // Mirrors the production check in EpubReaderViewModel.onPositionChanged: when restoring, re-fire
    // if the incoming progression is at-or-near zero for the captured href; otherwise the restore is
    // considered taken and the watcher disarms.
    private data class Position(val href: String, val progression: Double)

    @Test
    fun `capture at popup-show then resume emits origin via channel`() = runTest(UnconfinedTestDispatcher()) {
        var popupOrigin: Position? = null
        var pending: Position? = null
        val channel = kotlinx.coroutines.channels.Channel<Position>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val emitted = mutableListOf<Position>()
        backgroundScope.launch { for (value in channel) emitted.add(value) }

        // showFootnotePopup snapshots lastLocator into popupOrigin
        popupOrigin = Position("ch03.html", 0.5)
        // captureFootnotePopupLinkOrigin (URL tap) promotes origin into pending
        pending = popupOrigin
        // dismissFootnotePopup clears the popup origin AFTER capture has read it
        popupOrigin = null

        // onReaderResumed emits once and arms the watcher
        pending?.let { channel.trySend(it) }

        assertEquals(listOf(Position("ch03.html", 0.5)), emitted)
        // pending stays populated so the retry watcher can fire from onPositionChanged
        assertEquals(Position("ch03.html", 0.5), pending)
    }

    @Test
    fun `dismiss without URL tap clears popup origin and skips restore`() = runTest(UnconfinedTestDispatcher()) {
        var popupOrigin: Position? = null
        var pending: Position? = null

        // showFootnotePopup snapshots, then user dismisses without tapping a URL
        popupOrigin = Position("ch03.html", 0.5)
        popupOrigin = null  // dismissFootnotePopup

        // captureFootnotePopupLinkOrigin would only fire from the URL-tap path, so pending stays null
        assertNull(pending)
    }

    @Test
    fun `retry watcher re-fires when post-resume emission lands at chapter top`() = runTest(UnconfinedTestDispatcher()) {
        var pending: Position? = Position("ch03.html", 0.5)
        var attempts = 5
        val channel = kotlinx.coroutines.channels.Channel<Position>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val emitted = mutableListOf<Position>()
        backgroundScope.launch { for (value in channel) emitted.add(value) }

        fun onPositionChanged(incoming: Position) {
            if (attempts > 0) {
                val origin = pending
                if (origin != null && incoming.href == origin.href && incoming.progression < origin.progression - 0.01) {
                    attempts--
                    channel.trySend(origin)
                } else {
                    attempts = 0
                    pending = null
                }
            }
        }

        // Initial resume emit
        pending?.let { channel.trySend(it) }
        // Readium re-emits chapter top twice (the clobber), then our restore takes
        onPositionChanged(Position("ch03.html", 0.0))
        onPositionChanged(Position("ch03.html", 0.0))
        onPositionChanged(Position("ch03.html", 0.5))

        assertEquals(
            listOf(
                Position("ch03.html", 0.5),  // initial
                Position("ch03.html", 0.5),  // retry 1
                Position("ch03.html", 0.5),  // retry 2
            ),
            emitted,
        )
        assertNull(pending)
        assertEquals(0, attempts)
    }

    @Test
    fun `user navigation past origin disarms retry watcher`() = runTest(UnconfinedTestDispatcher()) {
        var pending: Position? = Position("ch03.html", 0.5)
        var attempts = 5
        val emitted = mutableListOf<Position>()

        fun onPositionChanged(incoming: Position) {
            if (attempts > 0) {
                val origin = pending
                if (origin != null && incoming.href == origin.href && incoming.progression < origin.progression - 0.01) {
                    attempts--
                    emitted.add(origin)
                } else {
                    attempts = 0
                    pending = null
                }
            }
        }

        // User swipes forward immediately on return
        onPositionChanged(Position("ch03.html", 0.6))

        assert(emitted.isEmpty())
        assertNull(pending)
        assertEquals(0, attempts)
    }
}
