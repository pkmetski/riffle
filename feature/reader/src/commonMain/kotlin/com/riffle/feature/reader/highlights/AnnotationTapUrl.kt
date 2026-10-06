package com.riffle.feature.reader.highlights

// Riffle-only URL scheme fired from the accent-bar tap span in synthesised Highlights-mode HTML.
// Both continuous and paginated/vertical reader modes intercept navigations targeting this scheme
// and route the annotation id back to the highlight-actions handler. The single hop through a URL
// is what makes the same injected HTML work in every reader mode.
const val ANNOTATION_TAP_URL_SCHEME = "riffle"
const val ANNOTATION_TAP_URL_AUTHORITY = "annotation-tap"
const val ANNOTATION_TAP_URL_PREFIX = "$ANNOTATION_TAP_URL_SCHEME://$ANNOTATION_TAP_URL_AUTHORITY/"

/**
 * Parsed form of a `riffle://annotation-tap/<id>` navigation, including the accent-bar element's
 * bounding rect in CSS pixels. Rect is null when the JS onclick couldn't produce one.
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

/** Build the URL an accent-bar tap element navigates to. Percent-encodes the annotation id. */
fun buildAnnotationTapUrl(annotationId: String): String =
    ANNOTATION_TAP_URL_PREFIX + annotationId.percentEncodeForUrl()

/** Parse a URL back into the annotation id it targets, or null if the URL is not one of ours. */
fun parseAnnotationTapUrl(url: String): String? = parseAnnotationTapUrlParts(url)?.annotationId

/** Full parse including the CSS-px rect params. */
fun parseAnnotationTapUrlParts(url: String): AnnotationTapUrlParts? {
    if (!url.startsWith(ANNOTATION_TAP_URL_PREFIX)) return null
    val after = url.removePrefix(ANNOTATION_TAP_URL_PREFIX)
    val idPart = after.substringBefore('?')
    if (idPart.isEmpty()) return null
    val decoded = idPart.percentDecodeFromUrl()
    if (decoded.isEmpty()) return null
    val query = if ('?' in after) after.substringAfter('?') else ""
    val params = parseQueryParams(query)
    return AnnotationTapUrlParts(
        annotationId = decoded,
        cssLeft = params["l"]?.toFloatOrNull(),
        cssTop = params["t"]?.toFloatOrNull(),
        cssRight = params["r"]?.toFloatOrNull(),
        cssBottom = params["b"]?.toFloatOrNull(),
    )
}

private fun parseQueryParams(query: String): Map<String, String> {
    if (query.isEmpty()) return emptyMap()
    return query.split('&').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        pair.substring(0, eq) to pair.substring(eq + 1)
    }.toMap()
}

/** RFC 3986 percent-encoding — encodes all chars that are not unreserved. */
internal fun String.percentEncodeForUrl(): String = buildString {
    for (c in this@percentEncodeForUrl) {
        if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~') {
            append(c)
        } else {
            val code = c.code
            if (code < 0x80) {
                append('%')
                append(code.toString(16).uppercase().padStart(2, '0'))
            } else {
                // Multi-byte UTF-8 encoding for non-ASCII chars.
                val utf8 = c.toString().encodeToByteArray()
                for (b in utf8) {
                    append('%')
                    append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
    }
}

internal fun String.percentDecodeFromUrl(): String = buildString {
    var i = 0
    val s = this@percentDecodeFromUrl
    while (i < s.length) {
        val c = s[i]
        when {
            c == '%' && i + 2 < s.length -> {
                val hex = s.substring(i + 1, i + 3)
                val byte = hex.toIntOrNull(16)
                if (byte != null) {
                    // Only single-byte values for annotation IDs (GUIDs are ASCII); fall back to
                    // raw char for anything multi-byte rather than failing hard.
                    append(byte.toChar())
                    i += 3
                } else {
                    append(c)
                    i++
                }
            }
            c == '+' -> { append(' '); i++ }
            else -> { append(c); i++ }
        }
    }
}
