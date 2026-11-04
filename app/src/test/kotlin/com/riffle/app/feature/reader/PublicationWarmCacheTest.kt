package com.riffle.app.feature.reader

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.readium.r2.shared.publication.Publication

class PublicationWarmCacheTest {

    private val cache = PublicationWarmCache()

    @Test
    fun `take returns null when cache is empty`() {
        assertNull(cache.take("item-1"))
    }

    @Test
    fun `take returns null for different itemId`() {
        val pub = mockk<Publication>(relaxed = true)
        cache.put("item-1", pub)
        assertNull(cache.take("item-2"))
    }

    @Test
    fun `take returns and clears cached publication for same itemId`() {
        val pub = mockk<Publication>(relaxed = true)
        cache.put("item-1", pub)
        val taken = cache.take("item-1")
        assertSame(pub, taken)
        assertNull(cache.take("item-1"))
    }

    @Test
    fun `put closes evicted publication when different itemId`() {
        val oldPub = mockk<Publication>(relaxed = true)
        val newPub = mockk<Publication>(relaxed = true)
        cache.put("item-1", oldPub)
        cache.put("item-2", newPub)
        verify(exactly = 1) { oldPub.close() }
    }

    @Test
    fun `put closes previous publication even when same itemId`() {
        val pub1 = mockk<Publication>(relaxed = true)
        val pub2 = mockk<Publication>(relaxed = true)
        cache.put("item-1", pub1)
        cache.put("item-1", pub2)
        verify(exactly = 1) { pub1.close() }
        assertSame(pub2, cache.take("item-1"))
    }

    @Test
    fun `clear closes and removes cached publication`() {
        val pub = mockk<Publication>(relaxed = true)
        cache.put("item-1", pub)
        cache.clear()
        verify(exactly = 1) { pub.close() }
        assertNull(cache.take("item-1"))
    }

    @Test
    fun `clear is a no-op when cache is empty`() {
        cache.clear()
        assertNull(cache.take("item-1"))
    }

    @Test
    fun `take after put round-trip returns correct publication`() {
        val pub = mockk<Publication>(relaxed = true)
        cache.put("book-42", pub)
        val taken = cache.take("book-42")
        assertNotNull(taken)
        assertSame(pub, taken)
    }
}
