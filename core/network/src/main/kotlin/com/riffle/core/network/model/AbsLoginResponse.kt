package com.riffle.core.network.model

internal data class AbsLoginResponse(val user: AbsUser) {
    data class AbsUser(val id: String, val username: String, val token: String)
}
