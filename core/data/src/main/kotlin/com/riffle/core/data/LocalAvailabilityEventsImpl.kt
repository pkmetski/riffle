package com.riffle.core.data

import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.StoredItemRef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class LocalAvailabilityEventsImpl constructor() : LocalAvailabilityEvents {
    private val _changes = MutableSharedFlow<StoredItemRef>(extraBufferCapacity = 64)
    override val changes: SharedFlow<StoredItemRef> = _changes.asSharedFlow()

    override fun notifyChanged(sourceId: String, itemId: String) {
        _changes.tryEmit(StoredItemRef(sourceId, itemId))
    }
}
