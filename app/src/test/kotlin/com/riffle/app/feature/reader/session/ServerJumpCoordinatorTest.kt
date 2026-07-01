package com.riffle.app.feature.reader.session

import android.net.FakeUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

@OptIn(ExperimentalCoroutinesApi::class)
class ServerJumpCoordinatorTest {

    @Suppress("UNCHECKED_CAST")
    private fun buildLocator(href: String, progression: Double = 0.0): Locator {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val url = unsafe.allocateInstance(AbsoluteUrl::class.java) as AbsoluteUrl
        AbsoluteUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
            .set(url, FakeUri(href))
        return Locator(
            href = url,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(progression = progression),
        )
    }

    @Test
    fun `requestJump emits to serverLocatorEvents`() = runTest(UnconfinedTestDispatcher()) {
        val sjc = ServerJumpCoordinator()
        val received = mutableListOf<Locator>()
        val job = launch { sjc.serverLocatorEvents.collect { received.add(it) } }
        sjc.requestJump(buildLocator("a.html", 0.1))
        job.cancel()
        assertEquals(1, received.size)
        assertEquals("a.html", received.first().href.toString())
    }

    @Test
    fun `markSuppressNext drops next requestJumpWithSuppressCheck exactly once`() = runTest(UnconfinedTestDispatcher()) {
        val sjc = ServerJumpCoordinator()
        val received = mutableListOf<Locator>()
        val job = launch { sjc.serverLocatorEvents.collect { received.add(it) } }
        sjc.markSuppressNext()
        sjc.requestJumpWithSuppressCheck(buildLocator("a.html"))
        assertEquals("first jump suppressed", 0, received.size)
        sjc.requestJumpWithSuppressCheck(buildLocator("b.html"))
        assertEquals("second jump passes", 1, received.size)
        job.cancel()
    }

    @Test
    fun `requestJump ignores suppress latch`() = runTest(UnconfinedTestDispatcher()) {
        val sjc = ServerJumpCoordinator()
        val received = mutableListOf<Locator>()
        val job = launch { sjc.serverLocatorEvents.collect { received.add(it) } }
        sjc.markSuppressNext()
        sjc.requestJump(buildLocator("a.html"))
        assertEquals("unconditional jump bypasses suppress", 1, received.size)
        job.cancel()
    }

    @Test
    fun `consumePendingStamp returns then clears`() {
        val sjc = ServerJumpCoordinator()
        assertNull(sjc.consumePendingStamp())
        sjc.setPendingStamp(42L)
        assertEquals(42L, sjc.consumePendingStamp())
        assertNull("stamp cleared after consumption", sjc.consumePendingStamp())
    }

    @Test
    fun `reset clears suppress and pending stamp`() = runTest(UnconfinedTestDispatcher()) {
        val sjc = ServerJumpCoordinator()
        sjc.markSuppressNext()
        sjc.setPendingStamp(7L)
        sjc.reset()
        val received = mutableListOf<Locator>()
        val job = launch { sjc.serverLocatorEvents.collect { received.add(it) } }
        sjc.requestJumpWithSuppressCheck(buildLocator("a.html"))
        assertEquals("reset cleared suppress", 1, received.size)
        assertNull("reset cleared pending stamp", sjc.consumePendingStamp())
        job.cancel()
    }
}
