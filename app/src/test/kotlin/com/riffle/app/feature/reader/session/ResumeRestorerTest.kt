package com.riffle.app.feature.reader.session

import android.net.FakeUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

class ResumeRestorerTest {

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

    private class Fixture {
        val refired = mutableListOf<Locator>()
        val restorer = ResumeRestorer(refire = { refired.add(it) })
    }

    @Test
    fun `setReturnAnchor does not overwrite existing anchor`() {
        val f = Fixture()
        f.restorer.setReturnAnchor(buildLocator("a.html", 0.5))
        f.restorer.setReturnAnchor(buildLocator("b.html", 0.3))
        assertEquals("first anchor wins", "a.html", f.restorer.peekReturnAnchor()?.href?.toString())
    }

    @Test
    fun `forceSetReturnAnchor overwrites existing anchor`() {
        val f = Fixture()
        f.restorer.setReturnAnchor(buildLocator("a.html", 0.5))
        f.restorer.forceSetReturnAnchor(buildLocator("b.html", 0.3))
        assertEquals("b.html", f.restorer.peekReturnAnchor()?.href?.toString())
    }

    @Test
    fun `consumeReturnAnchor round-trips then clears`() {
        val f = Fixture()
        f.restorer.setReturnAnchor(buildLocator("a.html", 0.5))
        assertEquals("a.html", f.restorer.consumeReturnAnchor()?.href?.toString())
        assertNull(f.restorer.consumeReturnAnchor())
    }

    @Test
    fun `armReturnRestore refires immediately and sets budget`() {
        val f = Fixture()
        val origin = buildLocator("a.html", 0.5)
        f.restorer.armReturnRestore(origin)
        assertEquals("armed anchor stored", "a.html", f.restorer.peekReturnAnchor()?.href?.toString())
        assertEquals("armed refire emitted", 1, f.refired.size)
    }

    @Test
    fun `onPositionEmitted refires when spurious chapter-top clobbers origin`() {
        val f = Fixture()
        f.restorer.armReturnRestore(buildLocator("a.html", 0.5))
        f.refired.clear()
        f.restorer.onPositionEmitted(buildLocator("a.html", 0.0))
        assertEquals("spurious clobber triggers refire", 1, f.refired.size)
        assertEquals("a.html", f.refired.first().href.toString())
    }

    @Test
    fun `onPositionEmitted stays armed when incoming is at origin`() {
        val f = Fixture()
        f.restorer.armReturnRestore(buildLocator("a.html", 0.5))
        f.refired.clear()
        // A near-origin emission means the restore took this round — don't disarm.
        f.restorer.onPositionEmitted(buildLocator("a.html", 0.5))
        assertEquals("no refire when at origin", 0, f.refired.size)
        assertEquals("anchor still armed", "a.html", f.restorer.peekReturnAnchor()?.href?.toString())
        // A later spurious clobber must still be re-fired.
        f.restorer.onPositionEmitted(buildLocator("a.html", 0.0))
        assertEquals("late clobber still re-fired", 1, f.refired.size)
    }

    @Test
    fun `onPositionEmitted disarms when user navigates to a different href`() {
        val f = Fixture()
        f.restorer.armReturnRestore(buildLocator("a.html", 0.5))
        f.refired.clear()
        f.restorer.onPositionEmitted(buildLocator("b.html", 0.0))
        assertEquals("no refire when href differs", 0, f.refired.size)
        assertNull("anchor cleared on navigation away", f.restorer.peekReturnAnchor())
    }

    @Test
    fun `onPositionEmitted disarms when progression moves past origin`() {
        val f = Fixture()
        f.restorer.armReturnRestore(buildLocator("a.html", 0.5))
        f.refired.clear()
        f.restorer.onPositionEmitted(buildLocator("a.html", 0.7))
        assertEquals("no refire when past origin", 0, f.refired.size)
        assertNull("anchor cleared", f.restorer.peekReturnAnchor())
    }

    @Test
    fun `budget bounds the number of refires`() {
        val f = Fixture()
        f.restorer.armReturnRestore(buildLocator("a.html", 0.5))
        f.refired.clear()
        // Budget starts at 5; ten spurious clobbers should refire at most 5 times.
        repeat(10) { f.restorer.onPositionEmitted(buildLocator("a.html", 0.0)) }
        assertTrue("bounded refires: got ${f.refired.size}", f.refired.size <= 5)
    }

    @Test
    fun `reset clears anchor and budget`() {
        val f = Fixture()
        f.restorer.armReturnRestore(buildLocator("a.html", 0.5))
        f.refired.clear()
        f.restorer.reset()
        f.restorer.onPositionEmitted(buildLocator("a.html", 0.0))
        assertEquals("no refire after reset", 0, f.refired.size)
        assertNull(f.restorer.peekReturnAnchor())
    }
}
