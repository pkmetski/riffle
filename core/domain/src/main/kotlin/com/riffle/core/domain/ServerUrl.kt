package com.riffle.core.domain

data class ServerUrl private constructor(val value: String) {

    companion object {
        private val ALLOWED_SCHEMES = setOf("http", "https")

        fun parse(raw: String): ServerUrl? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
            if (scheme !in ALLOWED_SCHEMES) return null

            val normalized = trimmed.trimEnd('/')
            return ServerUrl(normalized)
        }
    }
}
