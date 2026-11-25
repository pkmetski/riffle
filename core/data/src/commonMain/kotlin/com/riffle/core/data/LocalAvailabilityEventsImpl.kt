package com.riffle.core.data

import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.StoredItemRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class LocalAvailabilityEventsImpl constructor() : LocalAvailabilityEvents {
    private val _changes = MutableSharedFlow<StoredItemRef>(extraBufferCapacity = 64)
    override val changes: Flow<StoredItemRef> = _changes

    override fun notifyChanged(sourceId: String, itemId: String) {
        _changes.tryEmit(StoredItemRef(sourceId, itemId))
    }
}
