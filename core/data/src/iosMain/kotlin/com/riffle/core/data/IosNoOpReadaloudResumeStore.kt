package com.riffle.core.data

import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore

internal object IosNoOpReadaloudResumeStore : ReadaloudResumeStore {
    override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) = Unit
    override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
    override suspend fun clear(sourceId: String, itemId: String) = Unit
}
