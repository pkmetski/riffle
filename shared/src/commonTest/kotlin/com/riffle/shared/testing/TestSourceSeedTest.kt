package com.riffle.shared.testing

import com.riffle.core.models.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestSourceSeedTest {

    @Test
    fun parsesAbsSeedFromLaunchArguments() {
        val seed = TestSourceSeed.parse(
            listOf("--RIFFLE_RESET_FOR_TESTS", "--RIFFLE_SEED_SOURCE=ABS|http://127.0.0.1:5123|testuser|test"),
        )

        assertEquals(TestSourceSeed(SourceType.ABS, "http://127.0.0.1:5123", "testuser", "test"), seed)
        assertTrue(seed!!.insecureAllowed, "plain-HTTP stub servers need the insecure-connection consent")
    }

    @Test
    fun parsesKomgaSeedAndKeepsHttpsSecure() {
        val seed = TestSourceSeed.parse(listOf("--RIFFLE_SEED_SOURCE=KOMGA|https://komga.example|test@test.test|pw"))

        assertEquals(SourceType.KOMGA, seed?.type)
        assertEquals("test@test.test", seed?.username)
        assertFalse(seed!!.insecureAllowed)
    }

    @Test
    fun absentArgumentYieldsNull() {
        assertNull(TestSourceSeed.parse(listOf("--RIFFLE_RESET_FOR_TESTS")))
        assertNull(TestSourceSeed.parse(emptyList()))
    }

    @Test
    fun malformedArgumentYieldsNull() {
        assertNull(TestSourceSeed.parse(listOf("--RIFFLE_SEED_SOURCE=ABS|http://x|user")), "missing password")
        assertNull(TestSourceSeed.parse(listOf("--RIFFLE_SEED_SOURCE=NOPE|http://x|user|pw")), "unknown source type")
        assertNull(TestSourceSeed.parse(listOf("--RIFFLE_SEED_SOURCE=ABS||user|pw")), "empty url")
    }
}
