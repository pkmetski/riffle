package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderingTest {

    private fun lib(id: String) = Library(id = id, name = id, mediaType = "book", isUnsupported = false)

    @Test
    fun `empty order preserves the incoming order`() {
        val libs = listOf(lib("a"), lib("b"), lib("c"))
        assertEquals(libs, orderLibraries(libs, emptyList()))
    }

    @Test
    fun `full order reorders to match`() {
        val libs = listOf(lib("a"), lib("b"), lib("c"))
        assertEquals(
            listOf(lib("c"), lib("a"), lib("b")),
            orderLibraries(libs, listOf("c", "a", "b")),
        )
    }

    @Test
    fun `libraries not named in the order are appended in their incoming order`() {
        // 'd' was synced after the order was saved — it falls to the end, keeping its natural spot.
        val libs = listOf(lib("a"), lib("b"), lib("c"), lib("d"))
        assertEquals(
            listOf(lib("c"), lib("a"), lib("d")),
            orderLibraries(listOf(lib("a"), lib("c"), lib("d")), listOf("c", "a", "b")),
        )
        assertEquals(
            listOf(lib("b"), lib("a"), lib("c"), lib("d")),
            orderLibraries(libs, listOf("b", "a")),
        )
    }

    @Test
    fun `ids in the order that no longer match a library are skipped`() {
        val libs = listOf(lib("a"), lib("b"))
        assertEquals(
            listOf(lib("b"), lib("a")),
            orderLibraries(libs, listOf("gone", "b", "a")),
        )
    }
}
