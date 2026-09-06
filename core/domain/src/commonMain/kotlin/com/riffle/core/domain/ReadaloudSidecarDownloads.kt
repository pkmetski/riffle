package com.riffle.core.domain

interface ReadaloudSidecarDownloads {
    data class CachedSidecar(
        val storytellerSourceId: String,
        val storytellerBookId: String,
        val sizeBytes: Long,
    )

    fun listCached(): List<CachedSidecar>
    fun clearAll()
    fun remove(storytellerSourceId: String, storytellerBookId: String)
}
