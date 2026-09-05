package com.riffle.core.catalog.komga

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** `Authorization: Basic <base64>` header value for HTTP Basic auth. */
@OptIn(ExperimentalEncodingApi::class)
fun buildBasicAuthHeader(username: String, password: String): String {
    val token = Base64.encode("$username:$password".encodeToByteArray())
    return "Basic $token"
}
