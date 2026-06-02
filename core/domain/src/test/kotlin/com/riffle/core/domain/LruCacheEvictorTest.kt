package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LruCacheEvictorTest {

    private fun b(key: String, sizeMb: Long, lastAccessed: Long) =
        CachedBundle(key, sizeMb * 1024 * 1024, lastAccessed)

    @Test fun `nothing evicted when total is within the cap`() {
        val bundles = listOf(b("a", 500, 1), b("b", 400, 2))
        assertEquals(emptyList<String>(), LruCacheEvictor.selectForEviction(bundles, cap(2)))
    }

    @Test fun `evicts least-recently-accessed first until under the cap`() {
        val bundles = listOf(
            b("old", 800, 10),
            b("older", 800, 5),
            b("newest", 800, 100),
        )
        // total 2400MB, cap 2048MB -> must shed >=352MB -> evict the single oldest (800MB)
        assertEquals(listOf("older"), LruCacheEvictor.selectForEviction(bundles, cap(2)))
    }

    @Test fun `evicts multiple oldest entries when one is not enough`() {
        val bundles = listOf(
            b("a", 900, 1),
            b("b", 900, 2),
            b("c", 900, 3),
        )
        // total 2700MB, cap 1024MB -> evict a then b (1800 shed) leaving c=900 <= 1024
        assertEquals(listOf("a", "b"), LruCacheEvictor.selectForEviction(bundles, cap(1)))
    }

    @Test fun `cap of zero evicts everything`() {
        val bundles = listOf(b("a", 10, 1), b("b", 20, 2))
        assertEquals(listOf("a", "b"), LruCacheEvictor.selectForEviction(bundles, 0))
    }

    private fun cap(gb: Long) = gb * 1024 * 1024 * 1024
}
