package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsLoginResponse(val user: AbsUser) {
    @Serializable
    data class AbsUser(val id: String, val username: String, val token: String)
}
