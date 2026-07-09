package com.riffle.app.feature.reader

import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [FigureTapBridge]'s `onFigureLongPress` JS entry point — the paged/vertical counterpart to
 * [ChapterWebView.HeightBridge.onFigureLongPress] (continuous). Both delegate parsing to
 * [FigureLongPressMessageParser]; these tests exercise that parse through the bridge's registered
 * [android.webkit.JavascriptInterface] method, the same way `figure-tap.js` invokes it in the app.
 */
class FigureTapBridgeTest {

    @After
    fun tearDown() {
        // The handler registry is a global singleton (only one reader is open at a time) — reset it
        // so this test can't leak state into another test class run in the same JVM.
        FigureTapBridge.setLongPressHandler(null)
    }

    @Test
    fun `bridge parses long-press payload with href`() {
        val received = AtomicReference<FigureLongPressPayload?>()
        FigureTapBridge.setLongPressHandler { received.set(it) }

        FigureTapBridge.bridge.onFigureLongPress(
            """{"kind":"img","caption":"Fig 1","href":"a.png","svg":null,"elementId":null}""",
        )

        val p = received.get()
        assertNotNull(p)
        assertEquals("img", p!!.kind)
        assertEquals("Fig 1", p.caption)
        assertEquals("a.png", p.href)
        assertNull(p.svg)
        assertNull(p.elementId)
    }

    @Test
    fun `bridge parses long-press payload with svg`() {
        val received = AtomicReference<FigureLongPressPayload?>()
        FigureTapBridge.setLongPressHandler { received.set(it) }

        FigureTapBridge.bridge.onFigureLongPress(
            """{"kind":"svg","caption":"Diagram 2","href":null,"svg":"<svg></svg>","elementId":"fig-2"}""",
        )

        val p = received.get()
        assertNotNull(p)
        assertEquals("svg", p!!.kind)
        assertEquals("Diagram 2", p.caption)
        assertNull(p.href)
        assertEquals("<svg></svg>", p.svg)
        assertEquals("fig-2", p.elementId)
    }

    @Test
    fun `bridge parses long-press payload with rect`() {
        val received = AtomicReference<FigureLongPressPayload?>()
        FigureTapBridge.setLongPressHandler { received.set(it) }

        FigureTapBridge.bridge.onFigureLongPress(
            """{"kind":"img","caption":"Fig 1","href":"a.png","svg":null,"elementId":null,""" +
                """"rectX":12,"rectY":34,"rectW":200,"rectH":150}""",
        )

        val p = received.get()
        assertNotNull(p)
        assertEquals(12, p!!.rectX)
        assertEquals(34, p.rectY)
        assertEquals(200, p.rectW)
        assertEquals(150, p.rectH)
    }

    @Test
    fun `bridge missing rect fields defaults to zero`() {
        // Regression: an old-format JS payload (pre-rect rollout) must not crash the parser and
        // must not silently produce a garbage anchor — every rect field defaults to 0.
        val received = AtomicReference<FigureLongPressPayload?>()
        FigureTapBridge.setLongPressHandler { received.set(it) }

        FigureTapBridge.bridge.onFigureLongPress(
            """{"kind":"img","caption":"Fig 1","href":"a.png","svg":null,"elementId":null}""",
        )

        val p = received.get()
        assertNotNull(p)
        assertEquals(0, p!!.rectX)
        assertEquals(0, p.rectY)
        assertEquals(0, p.rectW)
        assertEquals(0, p.rectH)
    }

    @Test
    fun `bridge preserves JSON null as Kotlin null`() {
        // Regression: org.json.JSONObject#optString collapses a JSON null to "" — reverting the
        // `.takeIf { !obj.isNull(...) } ` guard in FigureLongPressMessageParser flips this red.
        val received = AtomicReference<FigureLongPressPayload?>()
        FigureTapBridge.setLongPressHandler { received.set(it) }

        FigureTapBridge.bridge.onFigureLongPress(
            """{"kind":"svg","caption":"Diagram","href":null,"svg":null,"elementId":null}""",
        )

        val p = received.get()
        assertNotNull(p)
        assertNull(p!!.svg)
        assertNull(p.href)
        assertNull(p.elementId)
    }
}
