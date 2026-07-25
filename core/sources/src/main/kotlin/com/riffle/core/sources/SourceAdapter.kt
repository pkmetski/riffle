package com.riffle.core.sources

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl

/**
 * Per-[SourceType] plug-in that owns the "user has typed URL + credentials" step of adding a
 * credentialed source. Implementations live in `core:sources` (pure JVM) so they can be tested
 * without the Android build toolchain. DI wiring via Hilt `@IntoMap` stays in `core:data`.
 */
interface SourceAdapter {
    val sourceType: SourceType

    suspend fun authenticate(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult
}
