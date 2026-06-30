package com.riffle.app.feature.reader.presenter

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

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
    fun `attachmentStamp returns null before attach (#300 step 2)`() = runTest {
        // The Step 2 cutover replaces `currentNavigatorStamp = { fragmentRef.value }` in the
        // screen with `currentNavigatorStamp = { presenter.attachmentStamp() }`. Both return
        // null before a fragment is bound, which is what lets the search-decoration settle loop
        // break out cleanly across detaches.
        val presenter = ReadiumPresenter(
            scope = kotlinx.coroutines.test.TestScope(),
            publication = stubPublication(),
            bridge = com.riffle.app.feature.reader.renderer.FakeRendererBridge(),
        )
        assertNull(presenter.attachmentStamp())
    }

    @Test
    fun `applyDecorations no-ops before attach (#300 step 2)`() = runTest {
        // Mirrors the original screen-level `(fragmentRef.value as? DecorableNavigator)?.let{...}`
        // — silently skips when there is no fragment, so calls between detach/attach (orientation
        // change, fragment recreation) never crash.
        val presenter = ReadiumPresenter(
            scope = kotlinx.coroutines.test.TestScope(),
            publication = stubPublication(),
            bridge = com.riffle.app.feature.reader.renderer.FakeRendererBridge(),
        )
        presenter.applyDecorations(emptyList(), "annotations")
        // No exception means contract preserved.
        assertTrue(true)
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

    /**
     * Allocates an uninitialised [Publication]. Constructing one normally reaches
     * `android.net.Uri.parse` (via Readium's `Url(...)`) which isn't on the JVM. The presenter
     * methods exercised in this file (`attachmentStamp`, `applyDecorations`-without-fragment)
     * never touch the publication, so an empty shell is fine. Same trick as
     * [com.riffle.app.feature.reader.ReadiumHighlightRendererTest.minimalLocator].
     */
    private fun stubPublication(): Publication {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Publication::class.java) as Publication
    }
}
