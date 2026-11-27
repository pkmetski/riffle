package com.riffle.core.domain

// Source-removal seam: drop a removed source's cached sidecars so a re-added source can't inherit stale ones.
fun interface ReadaloudSidecarPurger {
    fun purgeSource(storytellerSourceId: String)
}
