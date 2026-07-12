package com.riffle.app.ui.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the passthrough vs. Bearer-wrap contract of [String.asAuthHeader]. Komga stores its full
 * `Basic <base64>` header in the token slot; ABS stores an opaque bearer token. The helper must
 * detect an existing scheme and forward as-is, otherwise cover fetches would send
 * `Authorization: Bearer Basic <base64>` and Komga would reject them.
 */
class AuthHeaderTest {
    @Test fun `bare token gets Bearer prefix`() {
        assertEquals("Bearer abc123", "abc123".asAuthHeader())
    }

    @Test fun `Basic header passes through untouched`() {
        assertEquals("Basic dGVzdDp0ZXN0", "Basic dGVzdDp0ZXN0".asAuthHeader())
    }

    @Test fun `Bearer header is not double-wrapped`() {
        assertEquals("Bearer abc123", "Bearer abc123".asAuthHeader())
    }

    @Test fun `Digest header passes through untouched`() {
        assertEquals("Digest xyz", "Digest xyz".asAuthHeader())
    }

    @Test fun `empty string stays empty (distinguishable from bare Bearer)`() {
        // Coil rejects blank header NAMES but accepts blank VALUES; callers use "" to mean
        // "no token yet" and expect it to round-trip, not become "Bearer ".
        assertEquals("", "".asAuthHeader())
    }
}
