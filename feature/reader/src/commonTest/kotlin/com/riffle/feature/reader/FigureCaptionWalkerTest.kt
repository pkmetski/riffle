package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * String-shape assertions against [FigureCaptionWalker.CAPTION_RESOLVER_JS]. These run on both
 * Android (JVM) and iOS (Kotlin/Native via iosSimulatorArm64Test) so a change that breaks the
 * O'Reilly h6 caption path is caught on both platforms.
 *
 * Note: test names use camelCase (no backtick) because Kotlin/Native rejects backtick names
 * containing commas or parentheses.
 */
class FigureCaptionWalkerTest {

    @Test
    fun captionResolverChecksH6InsideFigureBeforeFallingBackToAltAttribute() {
        // O'Reilly EPUBs place the real caption in an <h6> inside the <figure> wrapper, and also
        // put accessibility alt-descriptions in the <img alt="…"> attribute. Without the h6 scan,
        // resolveCaption falls through to `alt` and returns "A pink chart … Description
        // automatically generated" instead of "Figure 5-4. The effect of…".
        //
        // The h6/h5/h4/h3 scan is gated on CAPTION_PREFIX_RX so accessibility alt-descriptions
        // placed in <h6> (which do NOT start with "Figure N") are excluded.
        //
        // Reverting to figcaption-only inside resolveFigcaptionElement — or moving the h6 scan
        // after the alt fallback — flips this red.
        val js = FigureCaptionWalker.CAPTION_RESOLVER_JS
        val resolveFnEnd = js.indexOf("function resolveTextPrefixElement(el)")
        val resolveFn = js.substring(0, resolveFnEnd)
        assertTrue(resolveFn.contains("'h6'"), "resolveFigcaptionElement must scan h6 (O'Reilly caption style)")
        assertTrue(resolveFn.contains("'h5'"), "resolveFigcaptionElement must scan h5")
        assertTrue(resolveFn.contains("'h4'"), "resolveFigcaptionElement must scan h4")
        assertTrue(resolveFn.contains("'h3'"), "resolveFigcaptionElement must scan h3")
        assertTrue(resolveFn.contains("CAPTION_PREFIX_RX.test"), "h6 scan inside resolveFigcaptionElement must be gated on CAPTION_PREFIX_RX")
        // h6 scan must appear BEFORE the 'alt' fallback in the full resolver.
        val h6ScanIdx = js.indexOf("'h6'")
        val altIdx = js.indexOf("'alt'")
        assertTrue(h6ScanIdx in 0 until altIdx, "h6 scan inside figure must precede the alt-attribute fallback in resolveCaption")
    }
}
