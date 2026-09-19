package com.riffle.feature.library

/**
 * Multiplatform equivalent of `java.net.URLEncoder.encode(this, "UTF-8")` — the
 * `application/x-www-form-urlencoded` form that Compose Navigation route arguments are built
 * with on Android, and the exact inverse of [urlDecode].
 *
 * Kept byte-for-byte compatible with the JVM implementation because already-persisted routes
 * (and the nav-graph templates that match them) assume that exact escaping:
 *  - `a-z A-Z 0-9 . - * _` pass through unchanged,
 *  - a space becomes `+` (not `%20`),
 *  - everything else is UTF-8 encoded and percent-escaped with UPPERCASE hex digits.
 */
fun String.urlFormEncode(): String {
    val out = StringBuilder(length)
    for (byte in encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val ch = code.toChar()
        when {
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == '.' || ch == '-' || ch == '*' || ch == '_' -> out.append(ch)
            ch == ' ' -> out.append('+')
            else -> {
                out.append('%')
                out.append(HEX[code shr 4])
                out.append(HEX[code and 0x0F])
            }
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
