package com.riffle.app.feature.reader

import com.riffle.core.domain.SentenceQuote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the two silent-drop paths that used to kill Continuous mode
 * Play-from-here (fix b660397). Both live in [handleContinuousPlayFromHere], extracted
 * from the coordinator so this suite can hit them without an Android WebView.
 *
 * Failure modes covered:
 *  - Ensure-quotes-ready is awaited BEFORE the sentence map is consulted. If a future
 *    edit reads `sentenceQuotesProvider()` first, `scoped` is empty at read-time and
 *    the tap vanishes on first play — the exact recipe that shipped and had to be
 *    fixed twice.
 *  - Bare-href fallback fires when neither the JS resolver nor the offline sentence
 *    matcher can produce a span id. Without the fallback the callback silently drops
 *    the tap; paginated has always had this fallback (see EpubReaderScreen line ~1437).
 *  - The happy path still emits `href#sid` when resolution succeeds.
 */
class ContinuousPlayFromHereTest {

    private val chapterHref = "text/chap01.html"
    private val selectedText = "any selection"

    private fun quote(highlight: String) =
        SentenceQuote(before = "", highlight = highlight, after = "")

    @Test
    fun `ensureSentenceQuotesReady is awaited before sentenceQuotesProvider is read`() = runTest {
        val readyGate = CompletableDeferred<Unit>()
        val readsBeforeGate = mutableListOf<String>()
        val readsAfterGate = mutableListOf<String>()
        var gateOpen = false

        handleContinuousPlayFromHere(
            chapterHref = chapterHref,
            selectedText = selectedText,
            evalJs = { _, cb -> cb(null) },
            ensureSentenceQuotesReady = {
                // Simulate the async build: return only after some signal — the caller
                // must have awaited this before touching the quote provider.
                gateOpen = true
                readyGate.complete(Unit)
            },
            sentenceQuotesProvider = {
                if (gateOpen) readsAfterGate += "quotes"
                else readsBeforeGate += "quotes"
                emptyMap()
            },
            sentenceChaptersProvider = {
                if (gateOpen) readsAfterGate += "chapters"
                else readsBeforeGate += "chapters"
                emptyMap()
            },
            onPlayFromHere = { /* ignored */ },
        )

        assertTrue(
            "sentenceQuotesProvider must not be read until ensureSentenceQuotesReady returns. " +
                "Reading before the await reintroduces the first-play race (fix b660397).",
            readsBeforeGate.isEmpty(),
        )
        assertTrue(
            "sentenceQuotesProvider and sentenceChaptersProvider must both be read AFTER the await.",
            readsAfterGate.containsAll(listOf("quotes", "chapters")),
        )
        assertTrue(readyGate.isCompleted)
    }

    @Test
    fun `bare chapter href fallback fires when JS resolver returns null AND offline matcher misses`() = runTest {
        var emittedRef: String? = null

        handleContinuousPlayFromHere(
            chapterHref = chapterHref,
            selectedText = selectedText,
            // JS resolver returns empty → no span id from geometry
            evalJs = { _, cb -> cb("\"\"") },
            ensureSentenceQuotesReady = { /* immediate */ },
            // Empty maps make the offline matcher (sentenceIdForSelection) also return null
            sentenceQuotesProvider = { emptyMap() },
            sentenceChaptersProvider = { emptyMap() },
            onPlayFromHere = { emittedRef = it },
        )

        assertEquals(
            "When neither resolver produces a span id, the callback must fall back to the bare " +
                "chapter href so the player can start at nearest-clip. Dropping the tap here is " +
                "the exact silent-drop bug that had shipped in Continuous mode.",
            chapterHref,
            emittedRef,
        )
    }

    @Test
    fun `href-plus-sid is emitted when JS resolver returns a span id`() = runTest {
        var emittedRef: String? = null

        handleContinuousPlayFromHere(
            chapterHref = chapterHref,
            selectedText = selectedText,
            evalJs = { _, cb -> cb("\"c001-s7\"") },
            ensureSentenceQuotesReady = { },
            sentenceQuotesProvider = { emptyMap() },
            sentenceChaptersProvider = { emptyMap() },
            onPlayFromHere = { emittedRef = it },
        )

        assertEquals("$chapterHref#c001-s7", emittedRef)
    }

    @Test
    fun `offline sentence matcher wins when JS resolver returns null but text matches a quote`() = runTest {
        var emittedRef: String? = null
        val selection = "botanist"
        val matchingQuoteId = "c007-s3"
        val matchingQuote = quote("But I'm a botanist, damn it.")

        handleContinuousPlayFromHere(
            chapterHref = chapterHref,
            selectedText = selection,
            evalJs = { _, cb -> cb(null) },
            ensureSentenceQuotesReady = { },
            sentenceQuotesProvider = { mapOf(matchingQuoteId to matchingQuote) },
            sentenceChaptersProvider = { mapOf(matchingQuoteId to chapterHref) },
            onPlayFromHere = { emittedRef = it },
        )

        assertEquals(
            "When the JS resolver returns null, the offline text matcher must be consulted " +
                "against the scoped-to-chapter quote map. If this drops to the bare-href " +
                "fallback despite a real text match, we've regressed the offline path.",
            "$chapterHref#c007-s3",
            emittedRef,
        )
    }

    @Test
    fun `no play-from-here is emitted when ensureSentenceQuotesReady throws`() = runTest {
        var emittedRef: String? = null

        val boom = runCatching {
            handleContinuousPlayFromHere(
                chapterHref = chapterHref,
                selectedText = selectedText,
                evalJs = { _, cb -> cb("\"c001-s1\"") },
                ensureSentenceQuotesReady = { error("simulated build failure") },
                sentenceQuotesProvider = { emptyMap() },
                sentenceChaptersProvider = { emptyMap() },
                onPlayFromHere = { emittedRef = it },
            )
        }

        assertTrue(
            "A failure in the sidecar/bundle build must propagate — a swallowed error would " +
                "quietly break Play-from-here on future taps without any user-visible signal.",
            boom.isFailure,
        )
        assertNull(
            "onPlayFromHere must NOT fire when the quotes-ready await threw.",
            emittedRef,
        )
    }
}
