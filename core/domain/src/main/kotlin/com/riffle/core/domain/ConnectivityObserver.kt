package com.riffle.core.domain

import kotlinx.coroutines.flow.StateFlow

interface ConnectivityObserver {
    val isOnline: StateFlow<Boolean>
}
