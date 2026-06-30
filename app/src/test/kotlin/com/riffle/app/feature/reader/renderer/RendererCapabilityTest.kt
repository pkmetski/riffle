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
