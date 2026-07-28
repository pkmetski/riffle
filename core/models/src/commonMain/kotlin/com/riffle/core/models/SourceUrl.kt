package com.riffle.core.models

@ConsistentCopyVisibility
data class SourceUrl private constructor(val value: String) {

    /** `host` or `host:port` extracted from [value], without user-info, path, query, or fragment. */
    fun authority(): String {
        val rawAuthority = value
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return rawAuthority.substringAfterLast('@')
    }

    companion object {
        private val ALLOWED_SCHEMES = setOf("http", "https")

        fun parse(raw: String): SourceUrl? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
            if (scheme !in ALLOWED_SCHEMES) return null

            val normalized = trimmed.trimEnd('/')
            return SourceUrl(normalized)
        }
    }
}
