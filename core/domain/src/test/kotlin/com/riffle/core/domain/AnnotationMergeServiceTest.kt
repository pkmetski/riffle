package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive unit tests for AnnotationMergeService.
 *
 * Covers last-write-wins (LWW) merge logic, tombstone handling, idempotence,
 * time collisions, edge cases, and cross-device edits.
 */
class AnnotationMergeServiceTest {

    private lateinit var service: AnnotationMergeService

    @Before
    fun setUp() {
        service = AnnotationMergeService()
    }

    // Test 1: Single annotation unchanged
    @Test
    fun `single annotation merges unchanged`() {
        // Arrange
        val annotation = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "sample text",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-a",
            updatedAt = 1000L,
            createdAt = 500L,
        )

        // Act
        val result = service.merge(parsed = listOf(annotation))

        // Assert
        assertEquals(1, result.size)
        assertEquals(annotation, result[0])
    }

    // Test 2: LWW precedence - highest updatedAt wins
    @Test
    fun `highest updatedAt wins in LWW merge`() {
        // Arrange
        val oldVersion = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "old text",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-a",
            updatedAt = 1000L,
            createdAt = 500L,
        )

        val newVersion = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:20",
            textSnippet = "new text",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "green",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-b",
            updatedAt = 2000L,
            createdAt = 500L,
        )

        // Act
        val result = service.merge(parsed = listOf(oldVersion, newVersion))

        // Assert
        assertEquals(1, result.size)
        assertEquals(newVersion, result[0])
    }

    // Test 3: Tombstone wins if it's the latest version
    @Test
    fun `tombstone (deleted=true) wins if latest updatedAt`() {
        // Arrange
        val originalAnnotation = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "text",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-a",
            updatedAt = 1000L,
            createdAt = 500L,
            deleted = false,
        )

        val tombstone = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "text",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-b",
            updatedAt = 2000L,
            createdAt = 500L,
            deleted = true,
        )

        // Act
        val result = service.merge(parsed = listOf(originalAnnotation, tombstone))

        // Assert
        assertEquals(1, result.size)
        assertTrue(result[0].deleted)
        assertEquals(2000L, result[0].updatedAt)
    }

    // Test 4: Tombstone can be resurrected by later edit
    @Test
    fun `later edit resurrects a deleted annotation`() {
        // Arrange
        val tombstone = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "text",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-a",
            updatedAt = 1500L,
            createdAt = 500L,
            deleted = true,
        )

        val resurrectingEdit = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:20",
            textSnippet = "new text",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "green",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-b",
            updatedAt = 2000L,
            createdAt = 500L,
            deleted = false,
        )

        // Act
        val result = service.merge(parsed = listOf(tombstone, resurrectingEdit))

        // Assert
        assertEquals(1, result.size)
        assertFalse(result[0].deleted)
        assertEquals("new text", result[0].textSnippet)
        assertEquals("green", result[0].color)
    }

    // Test 5: Merge is idempotent
    @Test
    fun `merge is idempotent - merge(merge(A)) equals merge(A)`() {
        // Arrange
        val annotations = listOf(
            W3CAnnotation(
                id = "uuid-1",
                cfi = "/2/4!/4/2/2,/1:0,/1:10",
                textSnippet = "text1",
                chapterHref = "chapter1.html",
                type = "HIGHLIGHT",
                color = "yellow",
                originDeviceId = "device-a",
                lastModifiedByDeviceId = "device-b",
                updatedAt = 1000L,
                createdAt = 500L,
            ),
            W3CAnnotation(
                id = "uuid-1",
                cfi = "/2/4!/4/2/2,/1:0,/1:20",
                textSnippet = "text2",
                chapterHref = "chapter1.html",
                type = "HIGHLIGHT",
                color = "green",
                originDeviceId = "device-a",
                lastModifiedByDeviceId = "device-c",
                updatedAt = 2000L,
                createdAt = 500L,
            ),
        )

        // Act
        val firstMerge = service.merge(parsed = annotations)
        val secondMerge = service.merge(parsed = firstMerge)

        // Assert
        assertEquals(firstMerge, secondMerge)
    }

    // Test 6: Empty input produces empty result
    @Test
    fun `empty input produces empty result`() {
        // Act
        val result = service.merge(parsed = emptyList())

        // Assert
        assertEquals(0, result.size)
    }

    // Test 7: Time collision tie-breaker - lexicographic deviceId wins
    @Test
    fun `same updatedAt uses deviceId lexicographic tie-breaker`() {
        // Arrange
        val sameTimeA = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "from device-z",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-z",
            updatedAt = 1000L,
            createdAt = 500L,
        )

        val sameTimeB = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:20",
            textSnippet = "from device-a",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "green",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-a",
            updatedAt = 1000L,
            createdAt = 500L,
        )

        // Act
        val result = service.merge(parsed = listOf(sameTimeA, sameTimeB))

        // Assert
        assertEquals(1, result.size)
        // maxWithOrNull with thenBy uses ascending order, so the higher lexicographic value wins
        // "device-z" > "device-a", so device-z wins
        assertEquals("from device-z", result[0].textSnippet)
        assertEquals("yellow", result[0].color)
    }

    // Test 8: Multiple different annotations coexist
    @Test
    fun `multiple different UUIDs coexist in result`() {
        // Arrange
        val annotation1 = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "text1",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-a",
            updatedAt = 1000L,
            createdAt = 500L,
        )

        val annotation2 = W3CAnnotation(
            id = "uuid-2",
            cfi = "/2/4!/4/2/2,/1:20,/1:30",
            textSnippet = "text2",
            chapterHref = "chapter1.html",
            type = "BOOKMARK",
            bookmarkTitle = "My bookmark",
            originDeviceId = "device-b",
            lastModifiedByDeviceId = "device-b",
            updatedAt = 2000L,
            createdAt = 1500L,
        )

        val annotation3 = W3CAnnotation(
            id = "uuid-3",
            cfi = "/2/4!/4/2/2,/1:40,/1:50",
            textSnippet = "text3",
            chapterHref = "chapter2.html",
            type = "HIGHLIGHT",
            color = "green",
            note = "This is a note",
            originDeviceId = "device-c",
            lastModifiedByDeviceId = "device-c",
            updatedAt = 1500L,
            createdAt = 1000L,
        )

        // Act
        val result = service.merge(parsed = listOf(annotation1, annotation2, annotation3))

        // Assert
        assertEquals(3, result.size)
        // Result should be sorted by ID
        assertEquals("uuid-1", result[0].id)
        assertEquals("uuid-2", result[1].id)
        assertEquals("uuid-3", result[2].id)
    }

    // Test 9: All deleted set - multiple tombstones
    @Test
    fun `multiple deleted annotations all included in result`() {
        // Arrange
        val annotation1 = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "text1",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-a",
            updatedAt = 1000L,
            createdAt = 500L,
            deleted = true,
        )

        val annotation2 = W3CAnnotation(
            id = "uuid-2",
            cfi = "/2/4!/4/2/2,/1:20,/1:30",
            textSnippet = "text2",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "green",
            originDeviceId = "device-b",
            lastModifiedByDeviceId = "device-b",
            updatedAt = 2000L,
            createdAt = 1500L,
            deleted = true,
        )

        // Act
        val result = service.merge(parsed = listOf(annotation1, annotation2))

        // Assert
        assertEquals(2, result.size)
        assertTrue(result[0].deleted)
        assertTrue(result[1].deleted)
    }

    // Test 10: Cross-device edits - chain of modifications
    @Test
    fun `cross-device edit chain - create, edit, delete`() {
        // Arrange
        // Device A creates the annotation
        val creation = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "original text",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            note = null,
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-a",
            updatedAt = 1000L,
            createdAt = 1000L,
            deleted = false,
        )

        // Device B edits the annotation (changes color and adds note)
        val edit = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "original text",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "green",
            note = "Added by device B",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-b",
            updatedAt = 2000L,
            createdAt = 1000L,
            deleted = false,
        )

        // Device C deletes it
        val deletion = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "original text",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "green",
            note = "Added by device B",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-c",
            updatedAt = 3000L,
            createdAt = 1000L,
            deleted = true,
        )

        // Act
        val result = service.merge(parsed = listOf(creation, edit, deletion))

        // Assert
        assertEquals(1, result.size)
        assertTrue(result[0].deleted)
        assertEquals("device-c", result[0].lastModifiedByDeviceId)
        assertEquals(3000L, result[0].updatedAt)
    }

    // Test 11: Merge with existing annotations
    @Test
    fun `merge parsed annotations with existing annotations`() {
        // Arrange
        val existing = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "old existing",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-a",
            updatedAt = 1000L,
            createdAt = 500L,
        )

        val parsed = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:20",
            textSnippet = "new parsed",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "green",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-b",
            updatedAt = 2000L,
            createdAt = 500L,
        )

        // Act
        val result = service.merge(parsed = listOf(parsed), existing = listOf(existing))

        // Assert
        assertEquals(1, result.size)
        assertEquals("new parsed", result[0].textSnippet)
        assertEquals("green", result[0].color)
        assertEquals(2000L, result[0].updatedAt)
    }

    // Test 12: Complex merge with mixed UUIDs, timestamps, and tombstones
    @Test
    fun `complex merge with multiple UUIDs, edits, and deletions`() {
        // Arrange
        val annotations = listOf(
            // UUID-1: created by A, edited by B with higher timestamp
            W3CAnnotation(
                id = "uuid-1",
                cfi = "/2/4!/4/2/2,/1:0,/1:10",
                textSnippet = "uuid-1 v1",
                chapterHref = "chapter1.html",
                type = "HIGHLIGHT",
                color = "yellow",
                originDeviceId = "device-a",
                lastModifiedByDeviceId = "device-a",
                updatedAt = 1000L,
                createdAt = 500L,
            ),
            W3CAnnotation(
                id = "uuid-1",
                cfi = "/2/4!/4/2/2,/1:0,/1:10",
                textSnippet = "uuid-1 v2",
                chapterHref = "chapter1.html",
                type = "HIGHLIGHT",
                color = "green",
                originDeviceId = "device-a",
                lastModifiedByDeviceId = "device-b",
                updatedAt = 2000L,
                createdAt = 500L,
            ),
            // UUID-2: standalone
            W3CAnnotation(
                id = "uuid-2",
                cfi = "/2/4!/4/2/2,/1:20,/1:30",
                textSnippet = "uuid-2",
                chapterHref = "chapter1.html",
                type = "BOOKMARK",
                bookmarkTitle = "Bookmark",
                originDeviceId = "device-c",
                lastModifiedByDeviceId = "device-c",
                updatedAt = 1500L,
                createdAt = 1000L,
            ),
            // UUID-3: created then deleted
            W3CAnnotation(
                id = "uuid-3",
                cfi = "/2/4!/4/2/2,/1:40,/1:50",
                textSnippet = "uuid-3",
                chapterHref = "chapter2.html",
                type = "HIGHLIGHT",
                color = "blue",
                originDeviceId = "device-d",
                lastModifiedByDeviceId = "device-d",
                updatedAt = 1800L,
                createdAt = 800L,
                deleted = false,
            ),
            W3CAnnotation(
                id = "uuid-3",
                cfi = "/2/4!/4/2/2,/1:40,/1:50",
                textSnippet = "uuid-3",
                chapterHref = "chapter2.html",
                type = "HIGHLIGHT",
                color = "blue",
                originDeviceId = "device-d",
                lastModifiedByDeviceId = "device-a",
                updatedAt = 2500L,
                createdAt = 800L,
                deleted = true,
            ),
        )

        // Act
        val result = service.merge(parsed = annotations)

        // Assert
        assertEquals(3, result.size)

        // UUID-1: should be the v2 (higher timestamp)
        assertEquals("uuid-1", result[0].id)
        assertEquals("uuid-1 v2", result[0].textSnippet)
        assertEquals("green", result[0].color)

        // UUID-2: standalone, unchanged
        assertEquals("uuid-2", result[1].id)
        assertEquals("Bookmark", result[1].bookmarkTitle)

        // UUID-3: should be the tombstone (highest timestamp)
        assertEquals("uuid-3", result[2].id)
        assertTrue(result[2].deleted)
    }

    // Test 13: Stable ordering of results
    @Test
    fun `merge results are always sorted by UUID`() {
        // Arrange
        val annotations = listOf(
            W3CAnnotation(
                id = "zzzz",
                cfi = "/2/4!/4/2/2,/1:0,/1:10",
                textSnippet = "z",
                chapterHref = "chapter1.html",
                type = "HIGHLIGHT",
                color = "yellow",
                originDeviceId = "device-a",
                lastModifiedByDeviceId = "device-a",
                updatedAt = 1000L,
                createdAt = 500L,
            ),
            W3CAnnotation(
                id = "aaaa",
                cfi = "/2/4!/4/2/2,/1:20,/1:30",
                textSnippet = "a",
                chapterHref = "chapter1.html",
                type = "HIGHLIGHT",
                color = "green",
                originDeviceId = "device-b",
                lastModifiedByDeviceId = "device-b",
                updatedAt = 2000L,
                createdAt = 1500L,
            ),
            W3CAnnotation(
                id = "mmmm",
                cfi = "/2/4!/4/2/2,/1:40,/1:50",
                textSnippet = "m",
                chapterHref = "chapter2.html",
                type = "HIGHLIGHT",
                color = "blue",
                originDeviceId = "device-c",
                lastModifiedByDeviceId = "device-c",
                updatedAt = 1500L,
                createdAt = 1000L,
            ),
        )

        // Act
        val result = service.merge(parsed = annotations)

        // Assert
        assertEquals(3, result.size)
        assertEquals("aaaa", result[0].id)
        assertEquals("mmmm", result[1].id)
        assertEquals("zzzz", result[2].id)
    }

    // Test 14: Tie-breaker with multiple candidates at same timestamp
    @Test
    fun `multiple tie-breaks applied in order`() {
        // Arrange - three versions of same UUID with same timestamp
        val versionX = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "device-x",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "yellow",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-x",
            updatedAt = 1000L,
            createdAt = 500L,
        )

        val versionB = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "device-b",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "green",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-b",
            updatedAt = 1000L,
            createdAt = 500L,
        )

        val versionZ = W3CAnnotation(
            id = "uuid-1",
            cfi = "/2/4!/4/2/2,/1:0,/1:10",
            textSnippet = "device-z",
            chapterHref = "chapter1.html",
            type = "HIGHLIGHT",
            color = "blue",
            originDeviceId = "device-a",
            lastModifiedByDeviceId = "device-z",
            updatedAt = 1000L,
            createdAt = 500L,
        )

        // Act
        val result = service.merge(parsed = listOf(versionX, versionB, versionZ))

        // Assert
        assertEquals(1, result.size)
        // maxWithOrNull with thenBy uses ascending order, so the highest lexicographic value wins
        // device-z is lexicographically highest of (b, x, z)
        assertEquals("device-z", result[0].lastModifiedByDeviceId)
    }
}
