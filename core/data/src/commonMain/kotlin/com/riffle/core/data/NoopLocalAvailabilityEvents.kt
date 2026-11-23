package com.riffle.core.data

import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.StoredItemRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

object NoopLocalAvailabilityEvents : LocalAvailabilityEvents {
    override val changes: Flow<StoredItemRef> = emptyFlow()
    override fun notifyChanged(sourceId: String, itemId: String) = Unit
}
