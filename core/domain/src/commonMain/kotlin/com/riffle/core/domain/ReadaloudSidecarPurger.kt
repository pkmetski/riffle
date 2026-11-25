package com.riffle.core.domain

/**
 * Source-removal seam: drop every cached sidecar owned by a removed source so a re-added source
 * doesn't inherit stale sidecars keyed to its predecessor's id.
 */
fun interface ReadaloudSidecarPurger {
    fun purgeSource(storytellerSourceId: String)
}
