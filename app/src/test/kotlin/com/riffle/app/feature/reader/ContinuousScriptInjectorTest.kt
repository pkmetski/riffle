package com.riffle.app.feature.reader

import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue("expected onFootnoteAnchorTap in $js", js.contains("onFootnoteAnchorTap"))
    }

    @Test
    fun `listener routes non-footnote anchors through onCrossReferenceTap`() {
        assertTrue("expected onCrossReferenceTap in $js", js.contains("onCrossReferenceTap"))
    }

    @Test
    fun `listener suppresses the WebView default scroll on every same-doc anchor tap`() {
        // The comment below the KDoc explains why: allowing the default in-page scroll to run
        // moves the child WebView's own scrollY, desyncing the parent's stacked-chapter geometry.
        // Regression assertion: onCrossReferenceTap must always be followed by preventDefault().
        val crossRefBranch = js.substringAfter("onCrossReferenceTap")
        assertTrue(
            "expected preventDefault() to run after onCrossReferenceTap in $js",
            crossRefBranch.contains("preventDefault"),
        )
    }

    @Test
    fun `listener uses a capture-phase click listener so it runs before default scroll`() {
        // The third argument to addEventListener is the capture flag; without it the default scroll
        // handler on the WebView would fire first.
        assertTrue(
            "expected capture-phase click listener in $js",
            js.contains("addEventListener('click'") && js.contains(", true)"),
        )
    }

    @Test
    fun `listener resolves path-prefixed hrefs against document location so same-chapter refs count as same-doc`() {
        // Regression: EPUBs frequently write same-chapter cross-references as full-path hrefs
        // ('part0007.xhtml#a2C8' clicked from part0007.xhtml) instead of bare '#a2C8'. The first
        // version of the fix skipped anything not starting with '#', which fell straight through
        // to WebView's default fragment scroll — no return card, and broke parent scroll continuity.
        // Assert the resolved-URL branch exists so this can't silently regress.
        assertTrue("expected URL resolution against document.location", js.contains("new URL(href, document.location.href)"))
        assertTrue("expected same-doc test against pathname", js.contains("resolved.pathname === document.location.pathname"))
        assertTrue("expected fragment extraction from resolved URL", js.contains("resolved.hash"))
    }

    @Test
    fun `listener defers truly cross-resource links to shouldOverrideUrlLoading`() {
        // We DO want a cross-resource link (part0008.xhtml#foo clicked from part0007.xhtml) to
        // fall through: the WebView's shouldOverrideUrlLoading path handles it via onInternalLink.
        // Regression assertion: the non-same-doc branch must NOT call onCrossReferenceTap.
        val crossResourceBranch = js.substringAfter("if (!sameDoc)").substringBefore("var id")
        assertTrue(
            "cross-resource branch must return without calling onCrossReferenceTap in $js",
            crossResourceBranch.contains("return") && !crossResourceBranch.contains("onCrossReferenceTap"),
        )
    }

    @Test
    fun `listener is idempotent per document`() {
        // Injected on every page load; guards keep it from stacking multiple handlers.
        assertTrue(js.contains("__riffleSameDocAnchorWired"))
    }
}
