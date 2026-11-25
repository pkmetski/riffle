package com.riffle.core.data.localfiles

import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityHasherTest {

    @Test
    fun `identical prefix and size produces identical hash`() {
        val prefix = "hello world".encodeToByteArray()
        assertEquals(IdentityHasher.hash(prefix, 42L), IdentityHasher.hash(prefix, 42L))
    }

    @Test
    fun `different sizes produce different hashes for the same prefix`() {
        val prefix = "hello".encodeToByteArray()
        assertNotEquals(IdentityHasher.hash(prefix, 100L), IdentityHasher.hash(prefix, 200L))
    }

    @Test
    fun `different prefixes produce different hashes for the same size`() {
        assertNotEquals(
            IdentityHasher.hash("hello".encodeToByteArray(), 5L),
            IdentityHasher.hash("world".encodeToByteArray(), 5L),
        )
    }

    @Test
    fun `source and prefix overloads agree at the 64KB boundary`() = runTest {
        val prefix = ByteArray(64 * 1024) { (it % 251).toByte() }
        val byArray = IdentityHasher.hash(prefix, prefix.size.toLong())
        val bySource = IdentityHasher.hash(Buffer().write(prefix), prefix.size.toLong())
        assertEquals(byArray, bySource)
    }

    @Test
    fun `source overload handles inputs shorter than 64KB`() = runTest {
        val bytes = ByteArray(1024) { (it % 97).toByte() }
        val byArray = IdentityHasher.hash(bytes, bytes.size.toLong())
        val bySource = IdentityHasher.hash(Buffer().write(bytes), bytes.size.toLong())
        assertEquals(byArray, bySource)
    }

    @Test
    fun `hash is 40 lowercase hex characters sha1`() {
        val hash = IdentityHasher.hash("x".encodeToByteArray(), 1L)
        assertEquals(40, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `hash stays byte-stable across releases`() {
        assertEquals("afee8cb6e87492cc3d6bf07e1c387cc8845b4177", IdentityHasher.hash("hello".encodeToByteArray(), 5L))
    }
}
