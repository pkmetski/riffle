package com.riffle.core.domain

import kotlinx.coroutines.flow.StateFlow

interface ConnectivityObserver {
    val isOnline: StateFlow<Boolean>

    /**
     * Whether the active network is metered (cellular, metered Wi-Fi, hotspot). Used to honour a
     * "Wi-Fi only" download choice. Defaults to false so non-Android fakes need not implement it.
     */
    fun isMetered(): Boolean = false

    /**
     * Re-synchronise [isOnline] with the current system state. Call from lifecycle-resume paths:
     * during doze/wake on Android 13+, `NetworkCallback` events can be coalesced or dropped, and
     * the event-derived tracker inside the observer then holds stale state indefinitely. This
     * hop reconciles it against `ConnectivityManager.getAllNetworks()`.
     */
    fun syncNow() {}
}
