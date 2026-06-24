package com.riffle.core.data

import android.util.Log
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AnnotationSyncTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "RIFFLE_ANNO_SYNC"

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
        Log.d(TAG, "AnnotationSyncTargetHolder constructed — starting config observer")
        scope.launch {
            configStore.observe().collectLatest { config ->
                val target = config?.let { factory.create(it) }
                Log.d(
                    TAG,
                    "Config changed → target=${if (target == null) "null" else "WebDav"} " +
                        "(config=${if (config == null) "absent" else "url=${config.baseUrl} user=${config.username}"})",
                )
                state.value = target
            }
        }
    }

    fun current(): AnnotationSyncTarget? = state.value
}
