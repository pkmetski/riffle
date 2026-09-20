package com.riffle.shared

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification

/**
 * iOS's "the app became active" signal, the host seam behind
 * [com.riffle.core.sync.ForegroundSyncDriver]'s foreground trigger (#1071 §14).
 *
 * `UIApplicationDidBecomeActiveNotification` also fires on cold launch, a moment after the
 * driver's own app-start pass — the driver's throttle exists precisely to coalesce that pair.
 *
 * The observer is registered once, for the process lifetime, and deliberately never removed:
 * this is a singleton owned by the Koin graph, which itself lives until process death. Replaying
 * nothing and dropping the oldest on overflow keeps a late subscriber from re-triggering a sweep
 * for a foreground that happened before it existed.
 */
class IosAppActiveEvents {
    private val _becameActive = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val becameActive: Flow<Unit> = _becameActive.asSharedFlow()

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            _becameActive.tryEmit(Unit)
        }
    }
}
