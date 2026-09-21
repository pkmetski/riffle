package com.riffle.feature.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [asAuthHeader] moved here with [CoverImage], which is its only shared caller. The contract is
 * unchanged and re-pinned on both platforms: Komga ships a full `Basic …` header value through
 * the same token slot Audiobookshelf uses for an opaque bearer token, and double-wrapping it
 * ("Bearer Basic …") is rejected by the server.
 */
class AuthHeaderTest {

    @Test
    fun opaqueTokenIsWrappedInBearer() {
        assertEquals("Bearer abc123", "abc123".asAuthHeader())
    }

    @Test
    fun anExistingSchemeIsPassedThrough() {
        assertEquals("Basic dGVzdDp0ZXN0", "Basic dGVzdDp0ZXN0".asAuthHeader())
        assertEquals("Bearer abc123", "Bearer abc123".asAuthHeader())
        assertEquals("Digest xyz", "Digest xyz".asAuthHeader())
    }

    @Test
    fun emptyTokenStaysEmptySoNoHeaderIsSent() {
        assertEquals("", "".asAuthHeader())
    }

    @Test
    fun coverErrorDetailNamesTheThrowableClass() {
        // The `err=` field of the RIFFLE_COVERS log line; `Throwable::class.simpleName` behaves
        // the same on Kotlin/Native as on the JVM for a named class.
        assertEquals("IllegalStateException", coverErrorDetail(IllegalStateException("boom")))
    }
}
