package com.riffle.app.feature.reader.renderer

import com.riffle.app.feature.reader.FootnoteAnchorBridge
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Pins the [RendererBridge] dependency graph: rect→toJSON polyfill must land BEFORE the footnote
 * bridge AND the selection-span tracker (both call getBoundingClientRect().toJSON() on API-25-era
 * WebViews where the polyfill is required). Capability registrations are the contract — a future
 * reorder is fine as long as the dep order still holds; renaming/removing a dep should fail here.
 */
class RendererCapabilityTest {

    private val capabilities = DefaultRendererBridge.defaultCapabilities(readaloudReserveProvider = { 0 })
    private val installOrder = topoSortCapabilities(capabilities)

    @Test fun `polyfill installs before its dependents`() {
        val polyIdx = installOrder.indexOfFirst { it.id == CapabilityId.RectToJsonPolyfill }
        val selIdx = installOrder.indexOfFirst { it.id == CapabilityId.SelectionSpanTracker }
        val footIdx = installOrder.indexOfFirst { it.id == CapabilityId.FootnoteAnchorBridge }
        assertNotEquals(-1, polyIdx)
        assertNotEquals(-1, selIdx)
        assertNotEquals(-1, footIdx)
        assertTrue("polyfill must precede selection tracker", polyIdx < selIdx)
        assertTrue("polyfill must precede footnote bridge", polyIdx < footIdx)
    }

    @Test fun `every capability that declares a dep has its dep installed earlier`() {
        installOrder.forEachIndexed { idx, cap ->
            cap.dependsOn.forEach { dep ->
                val depIdx = installOrder.indexOfFirst { it.id == dep }
                assertTrue("$dep should appear before ${cap.id}", depIdx in 0 until idx)
            }
        }
    }

    @Test fun `every registered capability is page-load scoped`() {
        // The bridge's installPageCapabilities() filters to PageLoad; today every capability is
        // PageLoad. If a new scope arrives, the bridge needs a new install hook AND this guard
        // needs to relax — the failure surfaces both at once.
        assertEquals(capabilities.map { it.scope }.toSet(), setOf(CapabilityScope.PageLoad))
    }

    @Test fun `install scripts reference their canonical JS markers`() {
        fun script(id: CapabilityId) = installOrder.first { it.id == id }.installScript()
        assertTrue(
            "rect polyfill must define toJSON",
            script(CapabilityId.RectToJsonPolyfill).contains("toJSON"),
        )
        assertTrue(
            "selection tracker must guard via __riffleSelTrackerInstalled",
            script(CapabilityId.SelectionSpanTracker).contains("__riffleSelTrackerInstalled"),
        )
        assertTrue(
            "footnote bridge must use the registered JS interface name",
            script(CapabilityId.FootnoteAnchorBridge).contains(FootnoteAnchorBridge.JS_NAME),
        )
        assertTrue(
            "readaloud reserve must apply on each install",
            script(CapabilityId.ReadaloudReserve).contains("riffle-readaloud-reserve"),
        )
        assertTrue(
            "settle backstop must guard via __riffleSettleSnapInstalled",
            script(CapabilityId.ScrollSettleBackstop).contains("__riffleSettleSnapInstalled"),
        )
        assertTrue(
            "decoration template re-register must call readium.registerDecorationTemplates",
            script(CapabilityId.DecorationTemplateReregister).contains("registerDecorationTemplates"),
        )
    }

    /**
     * Regression guard for the API-25 WebView (Chromium 52) missing modern DOM/JS APIs that
     * Readium 3.2.0+ needs to render decorations. Losing any of these polyfills silently breaks
     * annotation highlights in paginated + vertical modes: `document.body.append` blocks the
     * decoration group container from ever landing; `Object.entries` blocks
     * `registerDecorationTemplates` from populating its template map AND from injecting the
     * `.riffle-highlight-tint` CSS into the `<style>` head tag; `Array.prototype.flatMap` blocks
     * the TextQuote → DOM Range resolver, leaving every decoration with an empty `range: {}`.
     * Full RFC in the polyfill's own comment block.
     */
    @Test fun `rect polyfill installs every API missing on old-Chromium WebViews`() {
        val js = installOrder.first { it.id == CapabilityId.RectToJsonPolyfill }.installScript()
        val requiredMarkers = listOf(
            "toJSON",                       // ClientRect.toJSON polyfill
            "typeof proto.append",          // ChildNode/ParentNode.append polyfill
            "Object.entries = function",    // Object.entries polyfill
            "Object.values = function",     // Object.values polyfill
            "ResizeObserverPolyfill",       // ResizeObserver no-op stub
            "Array.prototype.flat",         // Array.prototype.flat polyfill
            "Array.prototype.flatMap",      // Array.prototype.flatMap polyfill
            "Object.fromEntries",           // Object.fromEntries polyfill
            "String.prototype.padStart",    // String.prototype.padStart polyfill
        )
        for (marker in requiredMarkers) {
            assertTrue(
                "rect polyfill script is missing marker \"$marker\" — a WebView-compat polyfill " +
                    "was dropped; annotation highlights will break on API-25-era WebViews",
                js.contains(marker),
            )
        }
    }

    /**
     * Readium only calls `registerDecorationTemplates` at resource-load — BEFORE our polyfills
     * run. On old WebViews the missing Object.entries silently short-circuits that first call, so
     * no template CSS lands and no annotation renders. We re-register templates after polyfills
     * install; if this dependency ever goes away, the re-register would race the polyfill and
     * hit the same broken engine.
     */
    @Test fun `decoration template re-register depends on the polyfill`() {
        val cap = installOrder.first { it.id == CapabilityId.DecorationTemplateReregister }
        assertTrue(
            "DecorationTemplateReregister must depend on RectToJsonPolyfill so it runs against a " +
                "polyfilled engine — otherwise Object.entries throws inside registerDecorationTemplates",
            CapabilityId.RectToJsonPolyfill in cap.dependsOn,
        )
        val polyIdx = installOrder.indexOfFirst { it.id == CapabilityId.RectToJsonPolyfill }
        val regIdx = installOrder.indexOfFirst { it.id == CapabilityId.DecorationTemplateReregister }
        assertTrue("polyfill must precede decoration re-register", polyIdx < regIdx)
    }

    /**
     * The re-register script must serialise the SAME [riffleDecorationTemplates] set the
     * fragment configuration passes to Readium. Otherwise the re-register would inject
     * different CSS from what the applied decorations reference, and the highlight class the
     * decoration `element` uses wouldn't have a matching stylesheet.
     */
    @Test fun `re-register script includes the riffle highlight tint class stylesheet`() {
        val js = installOrder.first { it.id == CapabilityId.DecorationTemplateReregister }.installScript()
        assertTrue(
            "re-register script must inline the .riffle-highlight-tint stylesheet",
            js.contains(".riffle-highlight-tint"),
        )
        assertTrue(
            "re-register script must inline the .riffle-note-glyph stylesheet",
            js.contains("riffle-note-glyph"),
        )
        assertTrue(
            "re-register script must be try/catch-guarded so a still-broken engine doesn't kill the surrounding capability chain",
            js.contains("try") && js.contains("catch"),
        )
    }

    @Test fun `readaloud reserve capability reads the live provider on every install`() {
        var live = 0
        val caps = DefaultRendererBridge.defaultCapabilities(readaloudReserveProvider = { live })
        val reserveCap = caps.first { it.id == CapabilityId.ReadaloudReserve }
        // 0 → no active class (off)
        assertTrue(reserveCap.installScript().contains("removeProperty"))
        live = 56
        // 56 → activate the reserve class
        assertTrue(reserveCap.installScript().contains("setProperty"))
    }

    @Test fun `topo sort rejects a cycle`() {
        val a = RendererCapability(
            id = CapabilityId.RectToJsonPolyfill,
            dependsOn = setOf(CapabilityId.SelectionSpanTracker),
            installScript = { "" },
        )
        val b = RendererCapability(
            id = CapabilityId.SelectionSpanTracker,
            dependsOn = setOf(CapabilityId.RectToJsonPolyfill),
            installScript = { "" },
        )
        try {
            topoSortCapabilities(listOf(a, b))
            fail("expected cycle to be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("cycle"))
        }
    }

    @Test fun `topo sort rejects a missing dependency`() {
        val a = RendererCapability(
            id = CapabilityId.RectToJsonPolyfill,
            dependsOn = setOf(CapabilityId.SelectionSpanTracker), // not registered
            installScript = { "" },
        )
        try {
            topoSortCapabilities(listOf(a))
            fail("expected missing dep to be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing"))
        }
    }
}
