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

    data class NavCall(val href: String, val progression: Float)

    private val calls = mutableListOf<NavCall>()

    private val fakeView = object : ContinuousNavigationView {
        override fun navigateTo(href: String, progression: Float) {
            calls.add(NavCall(href, progression))
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
    ) = Locator(
        href = makeAbsoluteUrl(urlString),
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(progression = progression),
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
}
