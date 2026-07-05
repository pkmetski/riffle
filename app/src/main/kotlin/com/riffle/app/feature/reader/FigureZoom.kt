package com.riffle.app.feature.reader

import org.json.JSONObject

/**
 * The reader's "figure zoom" overlay state. Non-null while a fullscreen zoomed view of the tapped
 * image is showing; null otherwise. Owned by the reader ViewModel and observed by
 * [EpubReaderScreen], which mounts [FigureZoomOverlay] above every reader mode.
 *
 * [href] is the EPUB-package-relative path to the image resource (used by
 * [FigureZoomOverlay] to load bytes via `Publication.get(href)`). [naturalWidth] / [naturalHeight]
 * are the image's intrinsic CSS pixel dimensions as reported by the JS hit-test — used to compute
 * the initial fit-to-screen size before the bitmap has decoded.
 *
 * Data URIs (`data:image/...;base64,...`) are supported by passing the encoded payload in [href];
 * the overlay decodes them directly without a Publication lookup. Inline SVGs are captured as
 * `<svg>` outerHTML by the hit-test and passed as `svgMarkup`; the overlay renders those via
 * WebView.
 */
internal data class FigureZoomState(
    val href: String,
    val naturalWidth: Int,
    val naturalHeight: Int,
    val svgMarkup: String? = null,
)

/**
 * Parse the JSON payload posted from `figure-tap.js` when the user taps a figure. The JS emits
 * `{ "kind": "img"|"svg", "href": "...", "w": <int>, "h": <int>, "svg": "..." }` — `href` is the
 * resolved src for `img`/`picture` targets (may be a `data:` URI), `svg` is the outerHTML for
 * inline SVG targets. Missing or malformed input returns null so the JS interface can't crash the
 * app on a badly-shaped tap.
 *
 * Extracted for JVM unit-testing so the schema is exercised without a live WebView; the JS is the
 * only writer, but a schema drift there is easy to make and hard to notice.
 */
internal object FigureTapMessageParser {
    fun parse(json: String?): FigureZoomState? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val kind = obj.optString("kind", "img")
        val w = obj.optInt("w", 0)
        val h = obj.optInt("h", 0)
        if (w <= 0 || h <= 0) return null
        return when (kind) {
            "svg" -> {
                val svg = obj.optString("svg", "")
                if (svg.isBlank()) return null
                FigureZoomState(href = "", naturalWidth = w, naturalHeight = h, svgMarkup = svg)
            }
            else -> {
                val href = obj.optString("href", "")
                if (href.isBlank()) return null
                FigureZoomState(href = href, naturalWidth = w, naturalHeight = h)
            }
        }
    }
}

/**
 * Clamp a scaled-image pan+zoom transform so the image can't be dragged off-screen.
 *
 * `scale` is clamped to `[minScale, maxScale]`. Translation is clamped so that when the image is
 * smaller than the viewport in either axis it stays centred (translation = 0), and when it is
 * larger the drag can move it exactly as far as the excess in that axis (half the excess in each
 * direction).
 *
 * Pure Kotlin so it JVM-unit-tests without a Compose runtime — the pinch-gesture composable
 * delegates all math here so a "double-tap resets and the image jumps 40px off-centre" regression
 * would flip a unit test, not require an emulator to catch.
 *
 * All lengths are in the same coordinate space (device px, CSS px, whatever the caller uses),
 * because the function only operates on ratios and differences.
 */
internal data class PanZoom(val scale: Float, val translationX: Float, val translationY: Float)

internal fun clampPanZoom(
    scale: Float,
    translationX: Float,
    translationY: Float,
    fittedWidth: Float,
    fittedHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    minScale: Float = 1f,
    maxScale: Float = 5f,
): PanZoom {
    val s = scale.coerceIn(minScale, maxScale)
    val scaledW = fittedWidth * s
    val scaledH = fittedHeight * s
    val maxX = (scaledW - viewportWidth).coerceAtLeast(0f) / 2f
    val maxY = (scaledH - viewportHeight).coerceAtLeast(0f) / 2f
    val tx = translationX.coerceIn(-maxX, maxX)
    val ty = translationY.coerceIn(-maxY, maxY)
    return PanZoom(s, tx, ty)
}

/** Plain-Kotlin size type so [fitImageIntoViewport] is trivially JVM-testable — Rect requires
 *  the Android framework which isn't linked in unit tests. */
internal data class FittedSize(val width: Int, val height: Int)

/**
 * Compute the fitted (initial-view) size of an image with intrinsic size
 * [naturalWidth] x [naturalHeight] inside a viewport of [viewportWidth] x [viewportHeight],
 * preserving aspect ratio. Fits by the more constraining axis, so the whole image is always
 * visible at scale = 1.
 */
internal fun fitImageIntoViewport(
    naturalWidth: Int,
    naturalHeight: Int,
    viewportWidth: Float,
    viewportHeight: Float,
): FittedSize {
    if (naturalWidth <= 0 || naturalHeight <= 0 || viewportWidth <= 0f || viewportHeight <= 0f) {
        return FittedSize(0, 0)
    }
    val imgAspect = naturalWidth.toFloat() / naturalHeight.toFloat()
    val vpAspect = viewportWidth / viewportHeight
    val (w, h) = if (imgAspect > vpAspect) {
        viewportWidth to viewportWidth / imgAspect
    } else {
        viewportHeight * imgAspect to viewportHeight
    }
    return FittedSize(w.toInt(), h.toInt())
}
