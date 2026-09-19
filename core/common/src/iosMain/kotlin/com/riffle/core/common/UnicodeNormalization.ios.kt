package com.riffle.core.common

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping

@OptIn(BetaInteropApi::class)
actual fun normalizeToNfd(s: String): String =
    NSString.create(string = s).decomposedStringWithCanonicalMapping
