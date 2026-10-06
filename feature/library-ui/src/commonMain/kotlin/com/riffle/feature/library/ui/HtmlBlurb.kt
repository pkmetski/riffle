package com.riffle.feature.library.ui

import androidx.compose.ui.text.AnnotatedString

/**
 * Renders a book description served as an HTML fragment.
 *
 * `expect`/`actual` because `AnnotatedString.fromHtml` is Android-only in Compose Multiplatform
 * 1.10. Android keeps rich rendering (`<b>`, `<i>`, `<br>`, entities); iOS falls back to
 * [stripHtmlTagsToText], which is shared so both platforms agree on the plain text.
 */
expect fun htmlBlurb(html: String): AnnotatedString

/**
 * Minimal HTML-to-plain-text: removes tags, decodes the five named entities + numeric references,
 * collapses runs of whitespace. Handles what ABS descriptions actually contain; not a full parser.
 */
internal fun stripHtmlTagsToText(html: String): String {
    val out = StringBuilder(html.length)
    var i = 0
    var inTag = false
    while (i < html.length) {
        val c = html[i]
        when {
            c == '<' -> { inTag = true; i++ }
            c == '>' -> { inTag = false; out.append(' '); i++ }
            inTag -> i++
            c == '&' -> {
                val end = html.indexOf(';', i)
                if (end in (i + 1)..(i + 10)) {
                    out.append(decodeHtmlEntity(html.substring(i + 1, end)))
                    i = end + 1
                } else { out.append(c); i++ }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}

private fun decodeHtmlEntity(body: String): String = when (body) {
    "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
    else -> when {
        body.startsWith("#x") || body.startsWith("#X") -> {
            val cp = body.drop(2).toIntOrNull(16)
            if (cp == null) "&$body;" else cp.codePointToString() ?: ""
        }
        body.startsWith("#") -> {
            val cp = body.drop(1).toIntOrNull()
            if (cp == null) "&$body;" else cp.codePointToString() ?: ""
        }
        else -> "&$body;"
    }
}

private fun Int.codePointToString(): String? = when {
    this <= 0 || this in 0xD800..0xDFFF || this > 0x10FFFF -> null
    this <= 0xFFFF -> this.toChar().toString()
    else -> {
        val v = this - 0x10000
        val high = (0xD800 + (v shr 10)).toChar()
        val low = (0xDC00 + (v and 0x3FF)).toChar()
        "$high$low"
    }
}
