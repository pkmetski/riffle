package com.riffle.core.domain

@ConsistentCopyVisibility
data class ServerUrl private constructor(val value: String) {

    /** `host` or `host:port` extracted from [value]; falls back to a substring parse if java.net.URI rejects the input. */
    fun authority(): String {
        return try {
            val uri = java.net.URI(value)
            val host = uri.host ?: return fallbackAuthority()
            if (uri.port > 0) "$host:${uri.port}" else host
        } catch (_: Exception) {
            fallbackAuthority()
        }
    }

    private fun fallbackAuthority(): String =
        value.substringAfter("://").substringBefore("/")

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
