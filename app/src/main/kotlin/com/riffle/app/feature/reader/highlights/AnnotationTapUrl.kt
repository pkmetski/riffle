package com.riffle.app.feature.reader.highlights

import java.net.URLDecoder
import java.net.URLEncoder

// Riffle-only URL scheme fired from the accent-bar tap span in synthesised Highlights-mode HTML.
// Both continuous ([com.riffle.app.feature.reader.ChapterWebView]) and paginated/vertical
// ([com.riffle.app.feature.reader.EpubReaderScreen]) intercept navigations targeting this scheme
// and route the annotation id back to the highlight-actions handler. The single hop through a URL
// is what makes the same injected HTML work in every reader mode — the JS bridge available in
// continuous does not exist in Readium's fragment WebViews, and the scheme string lets us bypass
// Readium's own external-link handling too (it treats non-http URLs as external anyway).
//
// The URL also carries the tap element's bounding rect in **CSS pixels** relative to the WebView's
// origin, encoded as `?l=&t=&r=&b=`. Both interceptors turn those into a window IntRect so the
// [com.riffle.app.feature.reader.HighlightActionsPopup] anchors next to the tapped accent bar
// instead of at the top-left of the screen.
const val ANNOTATION_TAP_URL_SCHEME = "riffle"
const val ANNOTATION_TAP_URL_AUTHORITY = "annotation-tap"
const val ANNOTATION_TAP_URL_PREFIX = "$ANNOTATION_TAP_URL_SCHEME://$ANNOTATION_TAP_URL_AUTHORITY/"

/**
 * Parsed form of a `riffle://annotation-tap/<id>` navigation, including the accent-bar element's
 * bounding rect in CSS pixels (see [buildAnnotationTapUrl]). Rect is null when the JS onclick
 * couldn't produce one (defensive — the emitter always writes it).
 */
data class AnnotationTapUrlParts(
    val annotationId: String,
    val cssLeft: Float?,
    val cssTop: Float?,
    val cssRight: Float?,
    val cssBottom: Float?,
) {
    fun hasRect(): Boolean =
        cssLeft != null && cssTop != null && cssRight != null && cssBottom != null
}

/**
 * Parse a URL back into the annotation id it targets, or null if the URL is not one of ours.
 * Round-trips [buildAnnotationTapUrl].
 */
fun parseAnnotationTapUrl(url: String): String? = parseAnnotationTapUrlParts(url)?.annotationId

/** Full parse including the CSS-px rect params. Round-trips the emitter in [buildAnnotationTapUrl]. */
fun parseAnnotationTapUrlParts(url: String): AnnotationTapUrlParts? {
    if (!url.startsWith(ANNOTATION_TAP_URL_PREFIX)) return null
    val after = url.removePrefix(ANNOTATION_TAP_URL_PREFIX)
    val idPart = after.substringBefore('?')
    if (idPart.isEmpty()) return null
    val decoded = try {
        // Two-arg (String, String) — the (String, Charset) overload is Java 10+ and does not
        // exist on API 25's runtime (NoSuchMethodError observed on the harness AVD).
        URLDecoder.decode(idPart, "UTF-8")
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (decoded.isEmpty()) return null
    val query = if ('?' in after) after.substringAfter('?') else ""
    val params = queryParams(query)
    return AnnotationTapUrlParts(
        annotationId = decoded,
        cssLeft = params["l"]?.toFloatOrNull(),
        cssTop = params["t"]?.toFloatOrNull(),
        cssRight = params["r"]?.toFloatOrNull(),
        cssBottom = params["b"]?.toFloatOrNull(),
    )
}

/** Build the URL an accent-bar tap element navigates to. Percent-encodes the annotation id. */
fun buildAnnotationTapUrl(annotationId: String): String =
    ANNOTATION_TAP_URL_PREFIX + URLEncoder.encode(annotationId, "UTF-8")

private fun queryParams(query: String): Map<String, String> {
    if (query.isEmpty()) return emptyMap()
    return query.split('&').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        pair.substring(0, eq) to pair.substring(eq + 1)
    }.toMap()
}
