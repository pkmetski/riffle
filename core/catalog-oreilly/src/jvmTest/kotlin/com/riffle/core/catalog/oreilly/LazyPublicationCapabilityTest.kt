package com.riffle.core.catalog.oreilly

import com.riffle.core.catalog.CatalogCapability
import com.riffle.core.catalog.LazyPublicationCapability
import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.catalog.LazySpineItem
import org.junit.Assert.assertEquals
import org.junit.Test

class LazyPublicationCapabilityTest {

    @Test
    fun `LazyPublicationCapability is a CatalogCapability`() {
        val fake = object : LazyPublicationCapability {
            override suspend fun lazyPublication(itemId: String) = null
            override suspend fun fetchChapterForLazy(itemId: String, fullPath: String, expectedByteSize: Long) = null
            override suspend fun fetchAssetForLazy(itemId: String, fullPath: String) = null
        }
        assert(fake is CatalogCapability)
    }

    @Test
    fun `LazyPublicationShape spine preserves order and sizes`() {
        val spine = listOf(
            LazySpineItem(0, "xhtml/ch01.xhtml", "Chapter 1", 50_000L, "application/xhtml+xml"),
            LazySpineItem(1, "xhtml/ch02.xhtml", "Chapter 2", 30_000L, "application/xhtml+xml"),
        )
        val pub = LazyPublicationShape(
            bookId = "9781234567890",
            identifier = "urn:orm:book:9781234567890",
            title = "Test Book",
            language = "en",
            spine = spine,
            absoluteFilesPrefix = "https://learning.oreilly.com/api/v2/epubs/urn:orm:book:9781234567890/files/",
            pathFilesPrefix = "/api/v2/epubs/urn:orm:book:9781234567890/files/",
            cssFullPaths = listOf("styles/main.css"),
        )
        assertEquals(2, pub.spine.size)
        assertEquals("xhtml/ch01.xhtml", pub.spine[0].fullPath)
        assertEquals(50_000L, pub.spine[0].declaredByteSize)
        assertEquals("xhtml/ch02.xhtml", pub.spine[1].fullPath)
        assertEquals(listOf("styles/main.css"), pub.cssFullPaths)
    }

    @Test
    fun `LazySpineItem index is preserved`() {
        val item = LazySpineItem(5, "xhtml/ch06.xhtml", "Chapter 6", 20_000L, "application/xhtml+xml")
        assertEquals(5, item.index)
        assertEquals("xhtml/ch06.xhtml", item.fullPath)
    }
}
