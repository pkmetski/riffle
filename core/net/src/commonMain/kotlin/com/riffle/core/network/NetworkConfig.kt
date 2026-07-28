package com.riffle.core.network

import kotlinx.serialization.json.Json

val RIFFLE_JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }
