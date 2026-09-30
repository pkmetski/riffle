package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the anchor-tap listener's dual-branch behaviour so regressions can't quietly return to the
 * pre-fix single-branch code. The old script only asked native whether the tapped anchor was a
 * footnote and let WebView's default in-page scroll run for everything else — that scroll shifted
 * child-WebView scrollY inside a stacked chapter, breaking the parent's continuous scroll AND
 * skipping the return-to-position card for figures / cross-references.
 */
class ContinuousScriptInjectorTest {

    private val js = ContinuousScriptInjector.SAME_DOC_ANCHOR_LISTENER_JS

    @Test
    fun `listener still routes footnote-style anchors through onFootnoteAnchorTap`() {
        assertTrue(js.contains("onFootnoteAnchorTap"), "expected onFootnoteAnchorTap in $js")
    }

    @Test
    fun `listener routes non-footnote anchors through onCrossReferenceTap`() {
        assertTrue(js.contains("onCrossReferenceTap"), "expected onCrossReferenceTap in $js")
    }

    @Test
    fun `listener suppresses the WebView default scroll on every same-doc anchor tap`() {
        // The comment below the KDoc explains why: allowing the default in-page scroll to run
        // moves the child WebView's own scrollY, desyncing the parent's stacked-chapter geometry.
        // Regression assertion: onCrossReferenceTap must always be followed by preventDefault().
        val crossRefBranch = js.substringAfter("onCrossReferenceTap")
        assertTrue(
            crossRefBranch.contains("preventDefault"),
            "expected preventDefault() to run after onCrossReferenceTap in $js",
        )
    }

    @Test
    fun `listener uses a capture-phase click listener so it runs before default scroll`() {
        // The third argument to addEventListener is the capture flag; without it the default scroll
        // handler on the WebView would fire first.
        assertTrue(
            js.contains("addEventListener('click'") && js.contains(", true)"),
            "expected capture-phase click listener in $js",
        )
    }

    @Test
    fun `listener resolves path-prefixed hrefs against document location so same-chapter refs count as same-doc`() {
        // Regression: EPUBs frequently write same-chapter cross-references as full-path hrefs
        // ('part0007.xhtml#a2C8' clicked from part0007.xhtml) instead of bare '#a2C8'. The first
        // version of the fix skipped anything not starting with '#', which fell straight through
        // to WebView's default fragment scroll — no return card, and broke parent scroll continuity.
        // Assert the resolved-URL branch exists so this can't silently regress.
        assertTrue(js.contains("new URL(href, document.location.href)"), "expected URL resolution against document.location")
        assertTrue(js.contains("resolved.pathname === document.location.pathname"), "expected same-doc test against pathname")
        assertTrue(js.contains("resolved.hash"), "expected fragment extraction from resolved URL hash")
    }

    @Test
    fun `listener defers truly cross-resource links to shouldOverrideUrlLoading`() {
        // We DO want a cross-resource link (part0008.xhtml#foo clicked from part0007.xhtml) to
        // fall through: the WebView's shouldOverrideUrlLoading path handles it via onInternalLink.
        // Regression assertion: the non-same-doc branch must NOT call onCrossReferenceTap.
        val crossResourceBranch = js.substringAfter("if (!sameDoc)").substringBefore("if (!id)")
        assertTrue(
            crossResourceBranch.contains("return") && !crossResourceBranch.contains("onCrossReferenceTap"),
            "cross-resource branch must return without calling onCrossReferenceTap in $js",
        )
    }

    @Test
    fun `listener skips URL parsing on the hot path when href starts with a bare hash`() {
        // Regression: URL parsing used to run unconditionally; the OR shortcut on href.charAt(0)
        // was only checked AFTER `new URL(...)` had already allocated. Most in-book anchors are
        // bare '#id' (this is what Readium emits for TOC entries and what most EPUBs use for
        // in-chapter cross-references), so the bare-hash branch must extract id WITHOUT going
        // through new URL().
        val bareHashBranch = js.substringAfter("if (href.charAt(0) === '#')").substringBefore("} else {")
        assertTrue(
            bareHashBranch.contains("href.substring(1)") && !bareHashBranch.contains("new URL"),
            "bare-hash branch must not call new URL() in $js",
        )
    }

    @Test
    fun `listener decodes percent-encoded fragment ids so getElementById matches raw DOM ids`() {
        // Regression: URL.hash preserves percent-encoding. An EPUB with '<a href="#figure%201">'
        // pointing at '<figure id="figure 1">' would extract id="figure%201" and native's
        // document.getElementById would silently fail. decodeURIComponent must run on the
        // path-prefixed branch (bare '#id' hrefs are already raw — no encoding survives
        // getAttribute).
        val pathPrefixedBranch = js.substringAfter("} else {").substringBefore("if (!id) return")
        assertTrue(
            pathPrefixedBranch.contains("decodeURIComponent"),
            "path-prefixed branch must decodeURIComponent the fragment id in $js",
        )
    }

    @Test
    fun `listener is idempotent per document`() {
        // Injected on every page load; guards keep it from stacking multiple handlers.
        assertTrue(js.contains("__riffleSameDocAnchorWired"))
    }

    // ── DOM-ready measurement support (cold-open latency, 2026-09-25) ───────

    @Test
    fun `height script exports report so the load event can remeasure without reinstalling`() {
        assertTrue(ContinuousScriptInjector.HEIGHT_MEASUREMENT_JS.contains("window.__riffleReport = report;"))
    }

    @Test
    fun `height script delays its first report until fonts are ready in DOM-ready mode`() {
        val hjs = ContinuousScriptInjector.HEIGHT_MEASUREMENT_JS
        assertTrue(hjs.contains("window.__riffleDomReadyMeasure"))
        assertTrue(hjs.contains("document.fonts.status !== 'loaded'"))
        assertTrue(hjs.contains("if (reportsHeld) return;"))
        assertTrue(hjs.contains("reportsHeld = true;"))
    }

    @Test
    fun `image reservation keeps author-set widths and ignores non-numeric attributes`() {
        val hjs = ContinuousScriptInjector.HEIGHT_MEASUREMENT_JS
        assertTrue(hjs.contains("if (Math.round(rw) === 300) {"))
        assertTrue(hjs.contains("/^\\s*\\d+\\s*(px)?\\s*$/.test(wa || '')"))
    }

    @Test
    fun `height script reserves sized image boxes in DOM-ready mode and releases them on load`() {
        val hjs = ContinuousScriptInjector.HEIGHT_MEASUREMENT_JS
        assertTrue(hjs.contains("im.getAttribute('width')"))
        assertTrue(hjs.contains("im.style.height = (rw * ah / aw) + 'px';"))
        assertTrue(hjs.contains("im.addEventListener('load', release, { once: true });"))
    }
}
