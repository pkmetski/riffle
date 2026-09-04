package com.riffle.core.common

import platform.Foundation.NSUUID

actual fun randomUuidString(): String = NSUUID.UUID().UUIDString().lowercase()
