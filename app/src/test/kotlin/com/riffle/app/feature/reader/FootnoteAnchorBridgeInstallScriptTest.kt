package com.riffle.app.feature.reader

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Paged and Vertical (Readium-driven) anchor listener's same-doc classification so it
 * matches the Continuous-mode listener's behaviour ([ContinuousScriptInjectorTest]). Before the
 * dual-shape change, the bridge only intercepted bare `#id` hrefs — a common EPUB idiom of writing
 * a same-chapter cross-reference as `part0007.xhtml#a2C8` (full path plus fragment) fell straight
 * through to the WebView's default in-page scroll, bypassing the [FootnoteAnchorBridge] →
 * `snapToElement` gate and the "already visible = no-op" contract with it.
 */
class FootnoteAnchorBridgeInstallScriptTest {

    private val js = FootnoteAnchorBridge.INSTALL_SCRIPT

    @Test
    fun `listener resolves path-prefixed hrefs against document location so same-chapter refs count as same-doc`() {
        assertTrue("expected URL resolution against document.location", js.contains("new URL(href, document.location.href)"))
        assertTrue("expected same-doc test against pathname", js.contains("resolved.pathname === document.location.pathname"))
        assertTrue("expected fragment extraction from resolved URL hash", js.contains("resolved.hash"))
    }

    @Test
    fun `listener defers truly cross-resource links to shouldOverrideUrlLoading`() {
        // A cross-resource link (part0008.xhtml#foo clicked from part0007.xhtml) must fall through
        // so Readium's shouldFollowInternalLink → EpubReaderViewModel.followInternalLink handles it.
        val crossResourceBranch = js.substringAfter("if (!sameDoc)").substringBefore("if (!id)")
        assertTrue(
            "cross-resource branch must return without calling onAnchorTap in $js",
            crossResourceBranch.contains("return") && !crossResourceBranch.contains("onAnchorTap"),
        )
    }

    @Test
    fun `listener skips URL parsing on the hot path when href starts with a bare hash`() {
        val bareHashBranch = js.substringAfter("if (href.charAt(0) === '#')").substringBefore("} else {")
        assertTrue(
            "bare-hash branch must not call new URL() in $js",
            bareHashBranch.contains("href.substring(1)") && !bareHashBranch.contains("new URL"),
        )
    }

    @Test
    fun `listener decodes percent-encoded fragment ids so getElementById matches raw DOM ids`() {
        // URL.hash preserves percent-encoding — an EPUB with <a href="#figure%201"> pointing at
        // <figure id="figure 1"> would extract id="figure%201" and getElementById would miss.
        val pathPrefixedBranch = js.substringAfter("} else {").substringBefore("if (!id) return")
        assertTrue(
            "path-prefixed branch must decodeURIComponent the fragment id in $js",
            pathPrefixedBranch.contains("decodeURIComponent"),
        )
    }

    @Test
    fun `listener still routes bare hash anchors through onAnchorTap`() {
        assertTrue("expected onAnchorTap in $js", js.contains("${FootnoteAnchorBridge.JS_NAME}.onAnchorTap"))
    }

    @Test
    fun `listener is idempotent per document`() {
        assertTrue(js.contains("__riffleFootnoteInstalled"))
    }
}
