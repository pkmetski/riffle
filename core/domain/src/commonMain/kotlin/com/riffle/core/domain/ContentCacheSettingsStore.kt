package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

enum class ContentCacheAutoClear(val days: Int?) {
    Off(days = null),
    After7Days(days = 7),
    After30Days(days = 30),
    After90Days(days = 90),
}

interface ContentCacheSettingsStore {
    val autoClear: Flow<ContentCacheAutoClear>

    suspend fun setAutoClear(value: ContentCacheAutoClear)

    companion object {
        val DEFAULT_AUTO_CLEAR: ContentCacheAutoClear = ContentCacheAutoClear.After30Days
    }
}
