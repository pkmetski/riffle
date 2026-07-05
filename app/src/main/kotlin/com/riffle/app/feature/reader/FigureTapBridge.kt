package com.riffle.app.feature.reader

import android.webkit.JavascriptInterface

/**
 * JS bridge that receives figure-tap events from paged/vertical mode's Readium WebViews. Continuous
 * mode routes the same event through the existing `RiffleChapter` object (see the `onFigureTap`
 * addition in [ChapterWebView.HeightBridge]) because that WebView already owns a per-chapter
 * bridge; adding another bridge just for figures would duplicate the wiring.
 *
 * The registry holds a single [handler] swapped in by the active reader — same shape as
 * [FootnoteAnchorBridge]. Only one reader is open at a time, so a global singleton is safe.
 */
internal object FigureTapBridge {
    const val JS_NAME: String = FigureTapScript.PAGED_BRIDGE_NAME

    @Volatile
    private var handler: ((String) -> Unit)? = null

    fun setHandler(h: ((String) -> Unit)?) {
        handler = h
    }

    val bridge: Bridge = Bridge()

    class Bridge {
        /** Called from JS with the JSON payload built by [FigureTapScript.installScript]. */
        @JavascriptInterface
        fun onFigureTap(payload: String) {
            handler?.invoke(payload)
        }
    }
}
