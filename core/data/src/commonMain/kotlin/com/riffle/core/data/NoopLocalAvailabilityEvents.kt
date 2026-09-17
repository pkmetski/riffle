package com.riffle.core.data

import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.StoredItemRef
import kotlinx.coroutines.flow.MutableSharedFlow

internal object NoopLocalAvailabilityEvents : LocalAvailabilityEvents {
    override val changes = MutableSharedFlow<StoredItemRef>()
    override fun notifyChanged(sourceId: String, itemId: String) = Unit
}
