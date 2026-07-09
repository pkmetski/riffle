package com.riffle.app.feature.reader

import android.net.FakeUri
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Regression coverage for the Annotations View → paginated/vertical "wrong chapter" bug.
 *
 * The chapter-landing symptom happened because `runReaderSyncCycle` writes the ABS last-read
 * position into the last-locator snapshot BEFORE the AndroidView factory runs, so the older
 * `latestLocator() ?: state.initialLocator` expression always shadowed the openAtCfi target
 * when opening from an annotation.
 *
 * The assertion that flips red on revert: with a non-null focusAnnotationId, the helper must
 * return `initial`, never `latest`.
 */
class InitialLocatorSelectionTest {

    @Suppress("UNCHECKED_CAST")
    private fun makeAbsoluteUrl(urlString: String): AbsoluteUrl {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val instance = unsafe.allocateInstance(AbsoluteUrl::class.java) as AbsoluteUrl
        val uriField = AbsoluteUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
        uriField.set(instance, FakeUri(urlString))
        return instance
    }

    private fun locator(href: String): Locator = Locator(
        href = makeAbsoluteUrl("https://example.com/$href"),
        mediaType = MediaType.XHTML,
    )

    @Test
    fun `focus annotation forces initial locator over latest`() {
        val latest = locator("ch5.xhtml")
        val initial = locator("ch2.xhtml")
        val picked = pickInitialLocator(
            focusAnnotationId = "ann-1",
            latest = latest,
            initial = initial,
        )
        assertSame(initial, picked)
    }

    @Test
    fun `focus annotation still returns initial when latest is null`() {
        val initial = locator("ch2.xhtml")
        val picked = pickInitialLocator(
            focusAnnotationId = "ann-1",
            latest = null,
            initial = initial,
        )
        assertSame(initial, picked)
    }

    @Test
    fun `focus annotation returns null initial as-is`() {
        val latest = locator("ch5.xhtml")
        val picked = pickInitialLocator(
            focusAnnotationId = "ann-1",
            latest = latest,
            initial = null,
        )
        assertNull(picked)
    }

    @Test
    fun `no focus annotation prefers latest over initial`() {
        val latest = locator("ch5.xhtml")
        val initial = locator("ch2.xhtml")
        val picked = pickInitialLocator(
            focusAnnotationId = null,
            latest = latest,
            initial = initial,
        )
        assertSame(latest, picked)
    }

    @Test
    fun `no focus annotation falls back to initial when latest is null`() {
        val initial = locator("ch2.xhtml")
        val picked = pickInitialLocator(
            focusAnnotationId = null,
            latest = null,
            initial = initial,
        )
        assertSame(initial, picked)
    }

    @Test
    fun `no focus annotation and no locators returns null`() {
        assertNull(
            pickInitialLocator(
                focusAnnotationId = null,
                latest = null,
                initial = null,
            )
        )
    }
}
