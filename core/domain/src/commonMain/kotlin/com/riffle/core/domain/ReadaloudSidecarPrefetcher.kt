package com.riffle.core.domain

fun interface ReadaloudSidecarPrefetcher {
    fun prepare(storytellerSourceId: String, storytellerBookId: String)
}
