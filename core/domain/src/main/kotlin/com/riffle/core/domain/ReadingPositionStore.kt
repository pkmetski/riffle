package com.riffle.core.domain

interface ReadingPositionStore {
    suspend fun save(itemId: String, cfi: String)
    suspend fun load(itemId: String): String?
    suspend fun loadLocalUpdatedAt(itemId: String): Long
    suspend fun updateLocalTimestamp(itemId: String, millis: Long)
}
