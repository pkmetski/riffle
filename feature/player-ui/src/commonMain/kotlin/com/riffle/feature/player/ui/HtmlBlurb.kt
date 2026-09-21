package com.riffle.feature.player.ui

import androidx.compose.ui.text.AnnotatedString

/**
 * Renders a book description, which Audiobookshelf serves as an HTML fragment.
 *
 * An `expect`/`actual` seam rather than shared code because `AnnotatedString.fromHtml` is an
 * Android-only API: Compose Multiplatform 1.10 does not publish it for Kotlin/Native. Android keeps
 * the rich rendering it has always had (`<b>`, `<i>`, `<br>` and entities) and iOS falls back to
 * [stripHtmlToText], which is shared so both platforms agree on what the *text* of a blurb is.
 *
 * Replace the iOS actual with a real parse — not a second stripper — if CMP ever ships `fromHtml`
 * for Native.
 */
expect fun htmlBlurb(html: String): AnnotatedString

/**
 * Tags out, entities in: `"<p>Hello&nbsp;<b>world</b></p>"` → `"Hello world"`.
 *
 * Deliberately not a parser. It handles what ABS descriptions actually contain: block tags that
 * should become a space, inline tags that should vanish, and the five named entities plus numeric
 * references. Runs of whitespace collapse so the removed markup does not leave gaps behind.
 */
fun stripHtmlToText(html: String): String {
    val out = StringBuilder(html.length)
    var i = 0
    var inTag = false
    while (i < html.length) {
        val c = html[i]
        when {
            c == '<' -> {
                inTag = true
                i++
            }
            c == '>' -> {
                inTag = false
                // A removed tag is a word boundary: "a<br>b" must not become "ab".
                out.append(' ')
                i++
            }
            inTag -> i++
            c == '&' -> {
                val end = html.indexOf(';', i)
                if (end in (i + 1)..(i + MAX_ENTITY_LENGTH)) {
                    out.append(decodeEntity(html.substring(i + 1, end)))
                    i = end + 1
                } else {
                    out.append(c)
                    i++
                }
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
}

private const val MAX_ENTITY_LENGTH = 10
private val WHITESPACE = Regex("\\s+")

private fun decodeEntity(body: String): String = when {
    body == "amp" -> "&"
    body == "lt" -> "<"
    body == "gt" -> ">"
    body == "quot" -> "\""
    body == "apos" -> "'"
    body == "nbsp" -> " "
    body.startsWith("#x") || body.startsWith("#X") ->
        body.drop(2).toIntOrNull(16)?.let { charFromCodePoint(it) } ?: "&$body;"
    body.startsWith("#") ->
        body.drop(1).toIntOrNull()?.let { charFromCodePoint(it) } ?: "&$body;"
    else -> "&$body;"
}

/** `StringBuilder.appendCodePoint` is JVM-only; this is the shared equivalent. */
private fun charFromCodePoint(codePoint: Int): String? = when {
    codePoint <= 0 || codePoint > MAX_CODE_POINT -> null
    codePoint <= BMP_MAX -> codePoint.toChar().toString()
    else -> {
        val v = codePoint - SUPPLEMENTARY_OFFSET
        val high = (SURROGATE_HIGH_BASE + (v shr 10)).toChar()
        val low = (SURROGATE_LOW_BASE + (v and 0x3FF)).toChar()
        "$high$low"
    }
}

private const val MAX_CODE_POINT = 0x10FFFF
private const val BMP_MAX = 0xFFFF
private const val SUPPLEMENTARY_OFFSET = 0x10000
private const val SURROGATE_HIGH_BASE = 0xD800
private const val SURROGATE_LOW_BASE = 0xDC00
