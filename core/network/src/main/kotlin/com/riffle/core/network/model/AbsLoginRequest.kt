package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsLoginRequest(val username: String, val password: String)
