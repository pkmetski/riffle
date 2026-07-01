package com.riffle.core.domain

import kotlinx.coroutines.flow.StateFlow

interface ConnectivityObserver {
    val isOnline: StateFlow<Boolean>

    /**
     * Whether the active network is metered (cellular, metered Wi-Fi, hotspot). Used to honour a
     * "Wi-Fi only" download choice. Defaults to false so non-Android fakes need not implement it.
     */
    fun isMetered(): Boolean = false
}
