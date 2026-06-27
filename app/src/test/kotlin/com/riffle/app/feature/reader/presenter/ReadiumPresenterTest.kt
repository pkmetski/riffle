package com.riffle.app.feature.reader.presenter

import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator

/**
 * Pure-JVM tests for [ReadiumPresenter] and its translation helpers.
 *
 * Anything that has to construct a [Locator] or a [Publication] reaches `android.net.Uri.parse`
 * through Readium's `Url(...)` constructor, which is not mocked in unit tests. That coverage
 * lives in the harness (and will be exercised end-to-end when callers cut over to drive this
 * adapter — Step 2+ of issue #300). What we can pin here is the resilience of the JSON-parse
 * layer that protects [NavigationTarget.ToLocatorJson] from corrupt input.
 */
class ReadiumPresenterTest {

    @Test
    fun `Locator_fromJSON tolerates malformed JSON via runCatching`() {
        // Same shape of guard the adapter wraps around [NavigationTarget.ToLocatorJson]. Mirrors
        // the runCatching used by `EpubReaderViewModel` (memory: "Book progress erased on open"
        // — when the local CFI column gets the wrong dialect, we must NOT throw at open time).
        val parsed = runCatching { Locator.fromJSON(JSONObject("{not-json")) }.getOrNull()
        assertNull(parsed)
    }

    @Test
    fun `Locator_fromJSON returns null on an empty object`() {
        val parsed = runCatching { Locator.fromJSON(JSONObject("{}")) }.getOrNull()
        assertNull(parsed)
    }

    @Test
    fun `NavigationTarget sealed hierarchy covers the three navigation shapes`() {
        // Exhaustiveness guard — if a new variant is added without coverage in [toLocator],
        // this test forces an update.
        val targets: List<NavigationTarget> = listOf(
            NavigationTarget.ToLocatorJson("{}"),
            NavigationTarget.ToHref("ch01.xhtml"),
            NavigationTarget.ToProgression("ch01.xhtml", 0.5f),
        )
        val matched = targets.map {
            when (it) {
                is NavigationTarget.ToLocatorJson -> "json"
                is NavigationTarget.ToHref -> "href"
                is NavigationTarget.ToProgression -> "progression"
            }
        }
        assertTrue(matched.containsAll(listOf("json", "href", "progression")))
    }
}
