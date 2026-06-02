package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossEpubIndexSerializerTest {

    @Test
    fun `an index round-trips through serialisation`() {
        val index = CrossEpubIndex(
            perChapter = listOf(
                ChapterCharMap(absChars = 12, storytellerChars = 12),
                ChapterCharMap(absChars = 200, storytellerChars = 100),
            ),
        )

        val blob = CrossEpubIndexSerializer.encode(index)

        assertEquals(index, CrossEpubIndexSerializer.decode(blob))
    }

    @Test
    fun `an empty index round-trips`() {
        val index = CrossEpubIndex(perChapter = emptyList())

        assertEquals(index, CrossEpubIndexSerializer.decode(CrossEpubIndexSerializer.encode(index)))
    }

    @Test
    fun `a malformed blob decodes to null rather than throwing`() {
        assertNull(CrossEpubIndexSerializer.decode("not json at all"))
    }
}
