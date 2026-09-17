package com.riffle.core.catalog.oreilly

fun parseOrmJwtFromCookieHeader(header: String?): String? {
    if (header.isNullOrBlank()) return null
    for (part in header.split(';')) {
        val trimmed = part.trim()
        if (trimmed.startsWith("orm-jwt=")) {
            val value = trimmed.removePrefix("orm-jwt=").trim()
            if (value.isNotBlank()) return value
        }
    }
    return null
}
