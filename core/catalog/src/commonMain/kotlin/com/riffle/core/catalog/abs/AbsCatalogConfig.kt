package com.riffle.core.catalog.abs

/**
 * Per-instance configuration for an [AbsCatalog]. Bound at repository wiring time (issue #434) —
 * one Source row on the DB corresponds to one [AbsCatalogConfig] and one [AbsCatalog] instance.
 */
data class AbsCatalogConfig(
    val baseUrl: String,
    val token: String,
    val insecureAllowed: Boolean,
    val deviceId: String,
)
