package com.riffle.core.data.localfiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class IdentityHasherTest {

    @Test
    fun `identical prefix and size produces identical hash`() {
        val prefix = "hello world".toByteArray()
        assertEquals(IdentityHasher.hash(prefix, 42L), IdentityHasher.hash(prefix, 42L))
    }

    @Test
    fun `different sizes produce different hashes for the same prefix`() {
        val prefix = "hello".toByteArray()
        assertNotEquals(IdentityHasher.hash(prefix, 100L), IdentityHasher.hash(prefix, 200L))
    }

    @Test
    fun `different prefixes produce different hashes for the same size`() {
        assertNotEquals(
            IdentityHasher.hash("hello".toByteArray(), 5L),
            IdentityHasher.hash("world".toByteArray(), 5L),
        )
    }

    @Test
    fun `stream and prefix overloads agree at the 64KB boundary`() {
        val prefix = ByteArray(64 * 1024) { (it % 251).toByte() }
        val byArray = IdentityHasher.hash(prefix, prefix.size.toLong())
        val byStream = IdentityHasher.hash(ByteArrayInputStream(prefix), prefix.size.toLong())
        assertEquals(byArray, byStream)
    }

    @Test
    fun `stream overload handles inputs shorter than 64KB`() {
        val bytes = ByteArray(1024) { (it % 97).toByte() }
        val byArray = IdentityHasher.hash(bytes, bytes.size.toLong())
        val byStream = IdentityHasher.hash(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(byArray, byStream)
    }

    @Test
    fun `hash is 40 hex characters (SHA-1)`() {
        val hash = IdentityHasher.hash("x".toByteArray(), 1L)
        assertEquals(40, hash.length)
        assert(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
