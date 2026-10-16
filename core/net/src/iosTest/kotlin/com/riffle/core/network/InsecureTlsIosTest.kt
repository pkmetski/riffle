package com.riffle.core.network

import kotlin.test.Test
import kotlin.test.assertNotSame

/**
 * Regression: withInsecureTls() on iOS was an identity function returning `this`, so the
 * "Allow insecure connection" toggle had no effect on iOS (#1242).
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
}
