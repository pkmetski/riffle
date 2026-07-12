package com.riffle.core.catalog.komga

import kotlinx.serialization.json.Json

internal val KomgaJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}
