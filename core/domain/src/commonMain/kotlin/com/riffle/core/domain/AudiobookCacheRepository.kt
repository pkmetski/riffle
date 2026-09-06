package com.riffle.core.domain

interface AudiobookCacheRepository {
    fun isCached(sourceId: String, itemId: String): Boolean
    suspend fun remove(sourceId: String, itemId: String): Long
}
