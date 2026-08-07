package com.riffle.app.feature.reader

import android.net.FakeUri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

class PdfLocatorGateTest {

    // Bypasses android.net.Uri by allocating AbsoluteUrl via Unsafe + FakeUri.
    @Suppress("UNCHECKED_CAST")
    private fun buildLocator(position: Int?): Locator {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val url = unsafe.allocateInstance(AbsoluteUrl::class.java) as AbsoluteUrl
        AbsoluteUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
            .set(url, FakeUri("file:///book.pdf"))
        return Locator(
            href = url,
            mediaType = MediaType.PDF,
            locations = Locator.Locations(position = position),
        )
    }

    /**
     * Regression for the Komga PDF progress-reset bug:
     *
     * PdfNavigatorViewModel seeds its `currentLocator` StateFlow with Locator.Locations() (all
     * null) before Pdfium renders any page. Without the fix, that null-position seed consumes the
     * guard, so the next emission (Pdfium landing on page 1) is treated as user navigation and
     * saved. A save of page 1 bumps localUpdatedAt to "now", and a LocalWins sync cycle then
     * pushes page 1 over the server's real progress (e.g. Komga page 50).
     *
     * Assertion that fails if the fix is reverted (null check removed from PdfLocatorGate):
     * [gate.advance(page1Locator)] returns false — the gate has NOT yet been consumed by the
     * null-position seed and correctly swallows the initial page-1 render.
     */
    @Test
    fun `null-position seed does not consume gate - initial page 1 is still swallowed`() {
        val gate = PdfLocatorGate()

        val seed = buildLocator(position = null)
        val page1 = buildLocator(position = 1)
        val page2 = buildLocator(position = 2)

        assertFalse("seed should be swallowed", gate.advance(seed))
        // Without the fix the seed would consume the guard; page1.advance() would then return true
        // (treated as user navigation), failing this assertion and triggering the progress reset.
        assertFalse("initial page-1 render should be swallowed (guard consumed here)", gate.advance(page1))
        assertTrue("subsequent page change should pass through", gate.advance(page2))
    }

    @Test
    fun `without seed - initial page is swallowed and subsequent pages pass through`() {
        val gate = PdfLocatorGate()
        assertFalse(gate.advance(buildLocator(position = 1)))
        assertTrue(gate.advance(buildLocator(position = 2)))
    }

    @Test
    fun `reset restores gate so the next initial page is swallowed again`() {
        val gate = PdfLocatorGate()
        assertFalse(gate.advance(buildLocator(position = 1)))
        assertTrue(gate.advance(buildLocator(position = 2)))

        gate.reset()

        assertFalse("after reset, seed should be swallowed again", gate.advance(buildLocator(null)))
        assertFalse("after reset, initial page should be swallowed again", gate.advance(buildLocator(1)))
        assertTrue("after reset, navigation should pass through again", gate.advance(buildLocator(2)))
    }

    @Test
    fun `multiple null-position seeds all swallowed without consuming gate`() {
        val gate = PdfLocatorGate()
        assertFalse(gate.advance(buildLocator(null)))
        assertFalse(gate.advance(buildLocator(null)))
        assertFalse(gate.advance(buildLocator(null)))
        // Guard still not consumed; page 1 should be swallowed
        assertFalse(gate.advance(buildLocator(1)))
        assertTrue(gate.advance(buildLocator(2)))
    }
}
