package com.riffle.core.common

import java.util.UUID

actual fun randomUuidString(): String = UUID.randomUUID().toString()
