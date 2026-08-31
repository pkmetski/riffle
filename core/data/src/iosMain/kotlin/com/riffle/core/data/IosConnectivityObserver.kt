package com.riffle.core.data

import com.riffle.core.domain.ConnectivityObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// TODO: implement with NWPathMonitor for real iOS connectivity tracking
class IosConnectivityObserver : ConnectivityObserver {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
    override fun isMetered(): Boolean = false
}
