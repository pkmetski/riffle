package com.riffle.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosLruCacheTest {

    @Test
    fun getReturnsNullWhenEmpty() {
        val cache = IosLruCache<Int, String>(10)
        assertNull(cache[0], "get on an empty cache must return null")
    }

    @Test
    fun getReturnsInsertedValue() {
        val cache = IosLruCache<Int, String>(10)
        cache[1] = "one"
        assertEquals("one", cache[1])
    }

    @Test
    fun evictsOldestEntryWhenFull() {
        val cache = IosLruCache<Int, String>(3)
        cache[0] = "a"
        cache[1] = "b"
        cache[2] = "c"
        cache[3] = "d"
        assertNull(cache[0], "oldest entry must be evicted when cache exceeds maxSize")
        assertEquals("d", cache[3])
    }

    @Test
    fun doesNotEvictWhenUnderCapacity() {
        val cache = IosLruCache<Int, String>(5)
        cache[0] = "a"
        cache[1] = "b"
        assertEquals("a", cache[0], "entries within capacity must not be evicted")
        assertEquals("b", cache[1])
    }

    @Test
    fun overwriteExistingKeyDoesNotGrowBeyondMaxSize() {
        val cache = IosLruCache<Int, String>(2)
        cache[0] = "a"
        cache[1] = "b"
        cache[0] = "a2"
        assertEquals("a2", cache[0], "overwriting an existing key must update the value")
        assertEquals("b", cache[1], "other entries must not be evicted on overwrite")
    }
}
