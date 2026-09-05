package com.riffle.core.catalog

data class CatalogHealth(
    val isReachable: Boolean,
    val serverVersion: String? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
)
