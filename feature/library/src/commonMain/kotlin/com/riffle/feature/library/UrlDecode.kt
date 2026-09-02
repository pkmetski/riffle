package com.riffle.feature.library

fun String.urlDecode(): String {
    val result = StringBuilder()
    var i = 0
    while (i < length) {
        when {
            get(i) == '%' && i + 2 < length -> {
                val hex = substring(i + 1, i + 3)
                result.append(hex.toInt(16).toChar())
                i += 3
            }
            get(i) == '+' -> {
                result.append(' ')
                i++
            }
            else -> {
                result.append(get(i))
                i++
            }
        }
    }
    return result.toString()
}
