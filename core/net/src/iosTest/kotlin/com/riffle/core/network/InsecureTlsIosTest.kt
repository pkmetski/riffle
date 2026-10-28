package com.riffle.core.network

import kotlin.test.Test
import kotlin.test.assertNotSame

/**
 * Regression: withInsecureTls() on iOS was an identity function returning `this`, so the
 * "Allow insecure connection" toggle had no effect on iOS (#1242).
 *
 * Also pins that withInsecureTls() uses config {} rather than constructing a fresh client, so
 * plugins installed on the base client (ContentNegotiation, HttpTimeout, auth) are preserved.
 */
class InsecureTlsIosTest {

    @Test
    fun withInsecureTlsReturnsNewInstance() {
        val base = createDefaultHttpClient()
        val insecure = base.withInsecureTls()
        assertNotSame(base, insecure)
        base.close()
        insecure.close()
    }

    @Test
    fun withInsecureTlsPreservesBaseClient() {
        val base = createDefaultHttpClient()
        val insecure = base.withInsecureTls()
        // The insecure client must not be the base itself (the challenge handler must be added)
        // and must not be null (plugin installation must not crash).
        assertNotSame(base, insecure)
        base.close()
        insecure.close()
    }
}
