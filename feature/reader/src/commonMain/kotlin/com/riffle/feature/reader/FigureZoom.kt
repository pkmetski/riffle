package com.riffle.feature.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The reader's "figure zoom" overlay state. Non-null while a fullscreen zoomed view of the tapped
 * image is showing; null otherwise.
 *
 * [href] is the EPUB-package-relative path to the image resource. [naturalWidth] / [naturalHeight]
 * are the image's intrinsic CSS pixel dimensions as reported by the JS hit-test — used to compute
 * the initial fit-to-screen size before the bitmap has decoded.
 *
 * Data URIs (`data:image/...;base64,...`) are supported by passing the encoded payload in [href];
 * the overlay decodes them directly without a Publication lookup. Inline SVGs are captured as
 * `<svg>` outerHTML by the hit-test and passed as `svgMarkup`; the overlay renders those via
 * WebView.
 */
data class FigureZoomState(
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
 */
object FigureTapMessageParser {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(json: String?): FigureZoomState? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching { lenient.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val kind = obj["kind"]?.jsonPrimitive?.content ?: "img"
        // intOrNull handles integer JSON values; the float fallback handles "800.0" from some
        // browsers that encode naturalWidth as a float when they shouldn't.
        val w = obj["w"]?.jsonPrimitive?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() } ?: 0
        val h = obj["h"]?.jsonPrimitive?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() } ?: 0
        if (w <= 0 || h <= 0) return null
        return when (kind) {
            "svg" -> {
                val svg = obj["svg"]?.jsonPrimitive?.content ?: ""
                if (svg.isBlank()) return null
                FigureZoomState(href = "", naturalWidth = w, naturalHeight = h, svgMarkup = svg)
            }
            else -> {
                val href = obj["href"]?.jsonPrimitive?.content ?: ""
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
 * Pure Kotlin so it JVM-unit-tests without a Compose runtime.
 */
data class PanZoom(val scale: Float, val translationX: Float, val translationY: Float)

fun clampPanZoom(
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

/** Plain-Kotlin size type so [fitImageIntoViewport] is trivially JVM-testable. */
data class FittedSize(val width: Int, val height: Int)

/**
 * Compute the fitted (initial-view) size of an image with intrinsic size
 * [naturalWidth] x [naturalHeight] inside a viewport of [viewportWidth] x [viewportHeight],
 * preserving aspect ratio.
 */
fun fitImageIntoViewport(
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
