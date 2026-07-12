package com.riffle.core.catalog.komga

import java.util.Base64

/** `Authorization: Basic <base64>` header value for HTTP Basic auth. */
fun buildBasicAuthHeader(username: String, password: String): String {
    val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    return "Basic $token"
}
