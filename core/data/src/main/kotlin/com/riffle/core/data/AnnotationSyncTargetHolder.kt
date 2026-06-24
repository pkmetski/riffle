package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AnnotationSyncTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds the currently-active [AnnotationSyncTarget], rebuilt on every config change.
 *
 * Provided in [com.riffle.core.data.di.DataModule] against an app-lifetime coroutine scope so
 * that saving a config in Settings is picked up by [AnnotationSyncController] without an app
 * restart.
 */
class AnnotationSyncTargetHolder(
    configStore: AnnotationSyncConfigStore,
    factory: WebDavAnnotationSyncTargetFactory,
    scope: CoroutineScope,
) {
    private val state = MutableStateFlow<AnnotationSyncTarget?>(null)

    init {
        scope.launch {
            configStore.observe().collectLatest { config ->
                state.value = config?.let { factory.create(it) }
            }
        }
    }

    fun current(): AnnotationSyncTarget? = state.value
}
