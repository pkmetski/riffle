package com.riffle.core.domain

interface ReadingPositionStore {
    suspend fun save(serverId: String, itemId: String, cfi: String)
    suspend fun load(serverId: String, itemId: String): String?
    suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long
    suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long)
}
