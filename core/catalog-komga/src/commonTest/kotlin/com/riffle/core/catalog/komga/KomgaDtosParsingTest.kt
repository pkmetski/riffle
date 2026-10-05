package com.riffle.core.catalog.komga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KomgaDtosParsingTest {

    @Test
    fun parsesLibraryDto() {
        val json = """{"id":"L1","name":"Comics","unavailable":false}"""
        val dto = KomgaJson.decodeFromString(KomgaLibraryDto.serializer(), json)
        assertEquals("L1", dto.id)
        assertEquals("Comics", dto.name)
        assertFalse(dto.unavailable)
    }

    @Test
    fun parsesLibraryDtoWithDefaultUnavailable() {
        val json = """{"id":"L2","name":"Manga"}"""
        val dto = KomgaJson.decodeFromString(KomgaLibraryDto.serializer(), json)
        assertEquals("L2", dto.id)
        assertFalse(dto.unavailable)
    }

    @Test
    fun parsesBookDtoMinimalFields() {
        val json = """{"id":"B1","libraryId":"L1","media":{},"metadata":{"authors":[]}}"""
        val dto = KomgaJson.decodeFromString(KomgaBookDto.serializer(), json)
        assertEquals("B1", dto.id)
        assertEquals("L1", dto.libraryId)
        assertNull(dto.seriesId)
        assertNull(dto.readProgress)
    }

    @Test
    fun parsesBookDtoWithReadProgress() {
        val json = """
            {
              "id":"B2","libraryId":"L1",
              "media":{"mediaType":"application/epub+zip","pagesCount":300},
              "metadata":{"title":"A Book","authors":[]},
              "readProgress":{"page":10,"completed":false,"lastModified":"2024-01-01T00:00:00Z"}
            }
        """.trimIndent()
        val dto = KomgaJson.decodeFromString(KomgaBookDto.serializer(), json)
        assertEquals("B2", dto.id)
        assertEquals("application/epub+zip", dto.media.mediaType)
        assertEquals("A Book", dto.metadata.title)
        val progress = assertNotNull(dto.readProgress)
        assertEquals(10, progress.page)
        assertFalse(progress.completed)
        assertEquals("2024-01-01T00:00:00Z", progress.lastModified)
    }

    @Test
    fun parsesReadProgressDtoDefaults() {
        val json = """{}"""
        val dto = KomgaJson.decodeFromString(KomgaReadProgressDto.serializer(), json)
        assertEquals(0, dto.page)
        assertFalse(dto.completed)
        assertNull(dto.readDate)
    }

    @Test
    fun ignoresUnknownFieldsInLibraryDto() {
        val json = """{"id":"L3","name":"Test","unavailable":false,"futureField":"ignored"}"""
        val dto = KomgaJson.decodeFromString(KomgaLibraryDto.serializer(), json)
        assertEquals("L3", dto.id)
    }

    @Test
    fun parsesReadListDto() {
        val json = """
            {"id":"RL1","name":"To Read","bookIds":["B1","B2"],"ownerId":"USER_ME"}
        """.trimIndent()
        val dto = KomgaJson.decodeFromString(KomgaReadListDto.serializer(), json)
        assertEquals("RL1", dto.id)
        assertEquals("To Read", dto.name)
        assertEquals(listOf("B1", "B2"), dto.bookIds)
        assertEquals("USER_ME", dto.ownerId)
    }

    @Test
    fun parsesReadListDtoWithNullOwnerId() {
        val json = """{"id":"RL2","name":"Old List","bookIds":[]}"""
        val dto = KomgaJson.decodeFromString(KomgaReadListDto.serializer(), json)
        assertEquals("RL2", dto.id)
        assertNull(dto.ownerId)
        assertTrue(dto.bookIds.isEmpty())
    }

    @Test
    fun parsesBookMediaProfile() {
        val json = """{"id":"B3","libraryId":"L1","media":{"mediaType":"application/epub+zip","mediaProfile":"DIVINA"},"metadata":{"authors":[]}}"""
        val dto = KomgaJson.decodeFromString(KomgaBookDto.serializer(), json)
        assertEquals("DIVINA", dto.media.mediaProfile)
    }
}
