package com.riffle.feature.library

fun String.urlDecode(): String {
    val result = StringBuilder()
    var i = 0
    while (i < length) {
        if (get(i) == '%' && i + 2 < length) {
            // Collect consecutive %XX sequences into a byte run and decode as UTF-8.
            val bytes = mutableListOf<Byte>()
            while (i < length && get(i) == '%' && i + 2 < length) {
                bytes.add(substring(i + 1, i + 3).toInt(16).toByte())
                i += 3
            }
            result.append(bytes.toByteArray().toString(Charsets.UTF_8))
        } else if (get(i) == '+') {
            result.append(' ')
            i++
        } else {
            result.append(get(i))
            i++
        }
    }
    return result.toString()
}
