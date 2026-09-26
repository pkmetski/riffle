package com.riffle.core.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The byte codecs behind the ABS-bookmark annotation shards (#1101 — ported from JVM-only
 * `MessageDigest`/`GZIPOutputStream` so iOS can read and write the same shards). Runs on the JVM
 * and the iOS simulator: a digest or framing difference between the two would make one platform
 * reject every shard the other wrote.
 */
class ByteCodecsTest {

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun sha256MatchesTheKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256(ByteArray(0)).hex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".encodeToByteArray()).hex())
    }

    @Test
    fun gzipRoundTripsAndUsesGzipFraming() {
        val payload = ("{\"annotations\":[" + "x".repeat(50_000) + "]}").encodeToByteArray()
        val packed = gzip(payload)
        assertEquals(0x1f, packed[0].toInt() and 0xFF, "gzip magic byte 1")
        assertEquals(0x8b, packed[1].toInt() and 0xFF, "gzip magic byte 2")
        assertTrue(packed.size < payload.size / 10, "repetitive payload compresses")
        assertContentEquals(payload, gunzip(packed))
    }

    @Test
    fun gzipRoundTripsEmptyAndTinyInputs() {
        assertContentEquals(ByteArray(0), gunzip(gzip(ByteArray(0))))
        assertContentEquals(byteArrayOf(7), gunzip(gzip(byteArrayOf(7))))
    }

    @Test
    fun gunzipRejectsGarbageAndTruncatedStreams() {
        assertFails { gunzip("not gzip".encodeToByteArray()) }
        val packed = gzip("hello hello hello hello".encodeToByteArray())
        assertFails { gunzip(packed.copyOf(packed.size / 2)) }
    }
}
