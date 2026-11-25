package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface LocalAvailabilityEvents {
    val changes: Flow<StoredItemRef>
    fun notifyChanged(sourceId: String, itemId: String)
}
