package com.riffle.core.domain

import kotlinx.coroutines.flow.SharedFlow

interface LocalAvailabilityEvents {
    val changes: SharedFlow<StoredItemRef>
    fun notifyChanged(sourceId: String, itemId: String)
}
