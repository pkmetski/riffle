package com.riffle.feature.reader

/**
 * The two pure *reads* of a rendered resource's scroll state, plus their result parsers.
 *
 * Both scripts used to be inline string literals in Android's `DefaultRendererBridge`, which is
 * why iOS's [EpubNavigatorInterface.scrollBoundary] answered [NavigatorScrollBoundary.None] and
 * its [EpubNavigatorInterface.viewportFractionEvents] was `emptyFlow()`: there was nothing to
 * call. They are shared here for the same reason [ColumnSnap] is — a host that re-types the
 * arithmetic gets a different answer for the same page, and nothing fails.
 *
 * Neither script writes. Both are safe to evaluate from a page-load or typography-change hook,
 * and [BOUNDARY_PROBE_JS] is additionally safe to poll while the reader is being touched: it is
 * what tells a scroll-mode reader that it is wedged against the end of a resource, which
 * Readium's locator progression cannot (it keeps re-emitting during a touch that moved nothing).
 */
object ScrollProbes {

    /**
     * True when the document is scrolled to its bottom.
     *
     * The 4 px slack absorbs sub-pixel rounding: on a non-integer device-pixel-ratio the sum of
     * `scrollY + innerHeight` lands a fraction short of `scrollHeight` at the true bottom, and an
     * exact comparison would mean the boundary is never reported on those devices.
     */
    const val AT_FORWARD_BOUNDARY_JS: String =
        "(window.scrollY + window.innerHeight >= document.body.scrollHeight - 4).toString()"

    /** True when the document is scrolled to its top. Same 4 px slack, same reason. */
    const val AT_BACKWARD_BOUNDARY_JS: String =
        "(window.scrollY <= 4).toString()"

    /**
     * Both boundaries in one round trip, as `"<forward>,<backward>"`.
     *
     * Android issues [AT_FORWARD_BOUNDARY_JS] and [AT_BACKWARD_BOUNDARY_JS] separately because it
     * polls them from a coroutine that already owns the main thread. iOS's every JS evaluation is
     * an async hop across the Kotlin/Swift boundary into `WKWebView.evaluateJavaScript`, so two
     * probes per tick is two hops and the pair can straddle a scroll frame — reporting "at the
     * top" and "at the bottom" for two different scroll positions. One script cannot.
     */
    const val BOUNDARY_PROBE_JS: String =
        "(function(){var atF=(window.scrollY + window.innerHeight >= document.body.scrollHeight - 4);" +
            "var atB=(window.scrollY <= 4);return atF+','+atB;})()"

    /**
     * Measure `viewportSize / scrollSize` for the currently loaded resource.
     *
     * Paginated mode overflows horizontally (`innerWidth / scrollWidth`); scrolling modes overflow
     * vertically (`innerHeight / scrollHeight`). The script picks the axis by whichever dimension
     * actually overflows, so one probe serves all three reading modes.
     *
     * Returns `""` when the ratio is not a usable positive finite number, which
     * [parseViewportFraction] turns into null so the caller publishes nothing rather than
     * poisoning [bookmarkEpsFor]'s live-measurement branch with a zero.
     */
    const val VIEWPORT_FRACTION_JS: String =
        "(function() {\n" +
            "  var iw = window.innerWidth, sw = document.documentElement.scrollWidth;\n" +
            "  var ih = window.innerHeight, sh = document.documentElement.scrollHeight;\n" +
            "  // Pick the overflow axis. Paginated overflows horizontally; vertical/no-overflow\n" +
            "  // fall through to the height ratio.\n" +
            "  var v = sw > iw ? (iw / sw) : (ih > 0 ? ih / sh : 0);\n" +
            "  return isFinite(v) && v > 0 ? v.toString() : \"\";\n" +
            "})()"

    /**
     * Parse [BOUNDARY_PROBE_JS]'s `"<forward>,<backward>"` payload.
     *
     * Tolerates the optional quote layer Android's `WebView.evaluateJavascript` adds and WKWebView
     * does not, exactly as every other shared parser in this package does. Anything unparseable
     * answers [NavigatorScrollBoundary.None] — "no boundary" is the safe default because it is the
     * answer that never fires a navigation.
     */
    fun parseScrollBoundary(raw: String?): NavigatorScrollBoundary {
        val trimmed = raw?.trim()?.trim('"')?.trim() ?: return NavigatorScrollBoundary.None
        val parts = trimmed.split(',')
        if (parts.size != 2) return NavigatorScrollBoundary.None
        return NavigatorScrollBoundary(
            atForwardBoundary = parts[0].trim().equals("true", ignoreCase = true),
            atBackwardBoundary = parts[1].trim().equals("true", ignoreCase = true),
        )
    }

    /** Parse a single boolean probe ([AT_FORWARD_BOUNDARY_JS] / [AT_BACKWARD_BOUNDARY_JS]). */
    fun parseBooleanProbe(raw: String?): Boolean =
        raw?.trim()?.trim('"')?.trim().equals("true", ignoreCase = true)

    /**
     * Parse [VIEWPORT_FRACTION_JS]. Null when the measurement is missing, empty, unparseable or
     * not a positive finite number.
     *
     * The positive-finite gate is the one Android's `ReadiumPresenter.publishViewportFraction`
     * already applies before it publishes, moved in here so both hosts share it. A value above
     * `1.0` is deliberately still published: it means the resource is shorter than the viewport,
     * and [bookmarkEpsFor] widening to the whole (short) chapter is the correct answer there.
     */
    fun parseViewportFraction(raw: String?): Double? {
        val trimmed = raw?.trim()?.trim('"')?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed == "null") return null
        val value = trimmed.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        return value
    }
}
