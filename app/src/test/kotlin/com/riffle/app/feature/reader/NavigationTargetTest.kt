package com.riffle.app.feature.reader

import android.net.FakeUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationTargetTest {

    data class NavCall(val href: String, val progression: Float, val alignToTop: Boolean)

    private val calls = mutableListOf<NavCall>()

    private val fakeView = object : ContinuousNavigationView {
        override fun navigateTo(href: String, progression: Float, alignToTop: Boolean) {
            calls.add(NavCall(href, progression, alignToTop))
        }
    }

    @Before
    fun setUp() {
        calls.clear()
    }

    // ---- helpers -------------------------------------------------------

    /**
     * Allocates an [AbsoluteUrl] without invoking its private constructor and injects a
     * [FakeUri] so that [AbsoluteUrl.toString] returns [urlString]. See
     * [ContinuousHighlightRendererTest.makeAbsoluteUrl] for rationale.
     */
    @Suppress("UNCHECKED_CAST")
    private fun makeAbsoluteUrl(urlString: String): AbsoluteUrl {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val instance = unsafe.allocateInstance(AbsoluteUrl::class.java) as AbsoluteUrl
        AbsoluteUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
            .set(instance, FakeUri(urlString))
        return instance
    }

    private fun makeLocator(
        urlString: String,
        progression: Double? = null,
        fragments: List<String> = emptyList(),
    ) = Locator(
        href = makeAbsoluteUrl(urlString),
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(progression = progression, fragments = fragments),
    )

    /** Returns a Locator with all fields null/zero (sufficient for pass-through tests). */
    @Suppress("UNCHECKED_CAST")
    private fun nullLocator(): Locator {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Locator::class.java) as Locator
    }

    // ---- ContinuousNavigationTarget ------------------------------------

    @Test
    fun `ContinuousNavigationTarget delegates href and progression to view`() = runTest {
        val target = ContinuousNavigationTarget { fakeView }
        val locator = makeLocator("https://example.com/ch1.xhtml", progression = 0.75)

        target.navigateTo(locator)

        assertEquals(1, calls.size)
        assertEquals("https://example.com/ch1.xhtml", calls[0].href)
        assertEquals(0.75f, calls[0].progression, 0.001f)
        assertEquals(false, calls[0].alignToTop)
    }

    @Test
    fun `ContinuousNavigationTarget uses zero progression when locator has none`() = runTest {
        val target = ContinuousNavigationTarget { fakeView }
        val locator = makeLocator("https://example.com/ch2.xhtml", progression = null)

        target.navigateTo(locator)

        assertEquals(1, calls.size)
        assertEquals(0f, calls[0].progression, 0.001f)
    }

    @Test
    fun `ContinuousNavigationTarget appends fragment anchor to href`() = runTest {
        val target = ContinuousNavigationTarget { fakeView }
        val locator = makeLocator(
            "https://example.com/ch3.xhtml",
            progression = 0.4,
            fragments = listOf("section-2"),
        )

        target.navigateTo(locator)

        assertEquals(1, calls.size)
        assertEquals("https://example.com/ch3.xhtml#section-2", calls[0].href)
    }

    @Test
    fun `ContinuousNavigationTarget uses first fragment only`() = runTest {
        val target = ContinuousNavigationTarget { fakeView }
        val locator = makeLocator(
            "https://example.com/ch4.xhtml",
            fragments = listOf("first", "second"),
        )

        target.navigateTo(locator)

        assertEquals("https://example.com/ch4.xhtml#first", calls[0].href)
    }

    @Test
    fun `ContinuousNavigationTarget forwards alignToTop to view`() = runTest {
        val target = ContinuousNavigationTarget { fakeView }
        val locator = makeLocator("https://example.com/ch5.xhtml", progression = 0.1)

        target.navigateTo(locator, alignToTop = true)

        assertEquals(1, calls.size)
        assertEquals(true, calls[0].alignToTop)
    }

    @Test
    fun `ContinuousNavigationTarget is a no-op when view is unavailable`() = runTest {
        val target = ContinuousNavigationTarget { null }
        val locator = makeLocator("https://example.com/ch3.xhtml", progression = 0.5)

        target.navigateTo(locator)

        assertEquals(0, calls.size)
    }

    // ---- ReadiumNavigationTarget ---------------------------------------

    @Test
    fun `ReadiumNavigationTarget delegates to go lambda`() = runTest {
        var received: Locator? = null
        val target = ReadiumNavigationTarget { locator -> received = locator }
        val locator = nullLocator()

        target.navigateTo(locator)

        assertSame(locator, received)
    }

    @Test
    fun `ReadiumNavigationTarget go lambda receives each navigateTo call`() = runTest {
        val received = mutableListOf<Locator>()
        val target = ReadiumNavigationTarget { locator -> received.add(locator) }
        val a = nullLocator()
        val b = nullLocator()

        target.navigateTo(a)
        target.navigateTo(b)

        assertEquals(2, received.size)
        assertSame(a, received[0])
        assertSame(b, received[1])
    }

    @Test
    fun `ReadiumNavigationTarget suspends until go completes`() = runTest {
        var completed = false
        val target = ReadiumNavigationTarget { kotlinx.coroutines.delay(1); completed = true }

        target.navigateTo(nullLocator())

        assertTrue(completed)
    }

    @Test
    fun `ReadiumNavigationTarget ignores alignToTop`() = runTest {
        val received = mutableListOf<Locator>()
        val target = ReadiumNavigationTarget { locator -> received.add(locator) }
        val locator = nullLocator()

        target.navigateTo(locator, alignToTop = true)

        assertEquals(1, received.size)
        assertSame(locator, received[0])
    }
}
