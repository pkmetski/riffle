package com.riffle.app.feature.reader.sentence

import com.riffle.app.feature.reader.presenter.ReadaloudFollowResult
import com.riffle.core.domain.SentenceQuote
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator

/**
 * Regression coverage for [performAutoFollow] — the auto-follow `LaunchedEffect` body extracted
 * from `EpubReaderScreen` (Task 6 of ADR 0039). Compose's `LaunchedEffect`/`@Composable` surface
 * isn't reachable from JVM unit tests (no `compose-ui-test` on this module's `test` source set),
 * so the branching decision is pulled into this standalone suspend function and exercised
 * directly with fakes.
 */
class SentencePlaybackControllerTest {

    private fun minimalLocator(): Locator {
        // Locator(Url, MediaType, ...) requires android.net.Uri which is not available in JVM unit
        // tests. Allocate a stub via Unsafe — mirrors ReadiumHighlightRendererTest's helper.
        // Assertions here only care about whether navigateToLocator was invoked, not its contents.
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Locator::class.java) as Locator
    }

    @Test
    fun `null activeFragmentRef does not call follow or navigate`() = runTest {
        var followCalled = false
        var navigateCalled = false

        performAutoFollow(
            activeFragmentRef = null,
            sentenceQuotes = emptyMap(),
            followReadaloudSentence = { followCalled = true; ReadaloudFollowResult.Snapped },
            fragmentLocator = { _, _ -> minimalLocator() },
            navigateToLocator = { navigateCalled = true },
        )

        assertTrue(!followCalled)
        assertTrue(!navigateCalled)
    }

    @Test
    fun `fragmentRef without a fragment id is skipped`() = runTest {
        var followCalled = false

        performAutoFollow(
            activeFragmentRef = "chapter1.xhtml", // no '#'
            sentenceQuotes = mapOf("s1" to SentenceQuote(before = "", highlight = "Hello", after = "")),
            followReadaloudSentence = { followCalled = true; ReadaloudFollowResult.Snapped },
            fragmentLocator = { _, _ -> minimalLocator() },
            navigateToLocator = {},
        )

        assertTrue(!followCalled)
    }

    @Test
    fun `missing quote for the fragment is skipped without calling follow`() = runTest {
        var followCalled = false

        performAutoFollow(
            activeFragmentRef = "chapter1.xhtml#s1",
            sentenceQuotes = emptyMap(), // quote not built yet
            followReadaloudSentence = { followCalled = true; ReadaloudFollowResult.Snapped },
            fragmentLocator = { _, _ -> minimalLocator() },
            navigateToLocator = {},
        )

        assertTrue(!followCalled)
    }

    @Test
    fun `Snapped result does not navigate`() = runTest {
        var navigateCalled = false

        performAutoFollow(
            activeFragmentRef = "chapter1.xhtml#s1",
            sentenceQuotes = mapOf("s1" to SentenceQuote(before = "", highlight = "Hello world", after = "")),
            followReadaloudSentence = { ReadaloudFollowResult.Snapped },
            fragmentLocator = { _, _ -> minimalLocator() },
            navigateToLocator = { navigateCalled = true },
        )

        assertTrue(!navigateCalled)
    }

    @Test
    fun `Unavailable result does not navigate`() = runTest {
        var navigateCalled = false

        performAutoFollow(
            activeFragmentRef = "chapter1.xhtml#s1",
            sentenceQuotes = mapOf("s1" to SentenceQuote(before = "", highlight = "Hello world", after = "")),
            followReadaloudSentence = { ReadaloudFollowResult.Unavailable },
            fragmentLocator = { _, _ -> minimalLocator() },
            navigateToLocator = { navigateCalled = true },
        )

        assertTrue(!navigateCalled)
    }

    @Test
    fun `OffPage result navigates using the fragment locator built from the quote`() = runTest {
        var navigatedLocator: Locator? = null
        var locatorRef: String? = null
        var locatorQuote: SentenceQuote? = null
        val quote = SentenceQuote(before = "prefix ", highlight = "Hello world", after = " suffix")
        val locator = minimalLocator()

        performAutoFollow(
            activeFragmentRef = "chapter1.xhtml#s1",
            sentenceQuotes = mapOf("s1" to quote),
            followReadaloudSentence = { text ->
                assertEquals("Hello world", text)
                ReadaloudFollowResult.OffPage
            },
            fragmentLocator = { ref, q ->
                locatorRef = ref
                locatorQuote = q
                locator
            },
            navigateToLocator = { navigatedLocator = it },
        )

        assertEquals("chapter1.xhtml#s1", locatorRef)
        assertEquals(quote, locatorQuote)
        assertEquals(locator, navigatedLocator)
    }

    @Test
    fun `OffPage result with a null locator does not navigate`() = runTest {
        var navigateCalled = false

        performAutoFollow(
            activeFragmentRef = "chapter1.xhtml#s1",
            sentenceQuotes = mapOf("s1" to SentenceQuote(before = "", highlight = "Hello world", after = "")),
            followReadaloudSentence = { ReadaloudFollowResult.OffPage },
            fragmentLocator = { _, _ -> null },
            navigateToLocator = { navigateCalled = true },
        )

        assertTrue(!navigateCalled)
    }
}
