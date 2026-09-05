package com.riffle.core.catalog

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class DestinationItemMatcherTest {
    @Test
    fun `matches by shared ISBN before display fields`() {
        val source = item(title = "Original title", author = "Original author", isbn = "978-1-2345-6789-0")
        val destination = item(title = "Translated title", author = "Different author", isbn = "9781234567890")

        assertTrue(doesDestinationItemExist(source, listOf(destination)))
    }

    @Test
    fun `falls back to normalized title and author`() {
        val source = item(title = "  A  Book ", author = "An Author")
        val destination = item(title = "a book", author = "an   author")

        assertTrue(doesDestinationItemExist(source, listOf(destination)))
    }

    @Test
    fun `does not match when identifiers disagree`() {
        val source = item(title = "Same title", author = "Same author", isbn = "978123")
        val destination = item(title = "Same title", author = "Same author", isbn = "978456")

        assertFalse(doesDestinationItemExist(source, listOf(destination)))
    }

    @Test
    fun `matches ABS item by author title folder when parsed tags differ`() {
        val source = item(title = "The Moon Room", author = "Valeri Petrov")
        val destination = item(
            title = "The Moon Room :narrator :radio",
            author = ":narrator ValeriPetrov",
        ).copy(path = "/audiobooks/Valeri Petrov/The Moon Room")

        assertTrue(doesDestinationItemExist(source, listOf(destination)))
    }

    private fun item(
        title: String,
        author: String,
        isbn: String? = null,
    ) = CatalogItem(
        id = title,
        rootId = "root",
        title = title,
        author = author,
        coverUrl = null,
        ebookFormat = BookFormat.Epub,
        isbn = isbn,
    )
}
