package com.riffle.core.network

import com.riffle.core.domain.InsecureConnectionType

sealed class NetworkStorytellerLoginResult {
    data class Success(val token: String) : NetworkStorytellerLoginResult()
    data class WrongCredentials(val message: String) : NetworkStorytellerLoginResult()
    data class NetworkError(val cause: Throwable) : NetworkStorytellerLoginResult()
    data class InsecureConnection(val type: InsecureConnectionType) : NetworkStorytellerLoginResult()
}
