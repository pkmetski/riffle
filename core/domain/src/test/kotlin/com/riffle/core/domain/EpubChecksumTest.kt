package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EpubChecksumTest {

    @Test
    fun `the same bytes always produce the same checksum`() {
        val bytes = "epub-contents".toByteArray()

        assertEquals(EpubChecksum.of(bytes), EpubChecksum.of(bytes.copyOf()))
    }

    @Test
    fun `different bytes produce different checksums, invalidating a stale index row`() {
        assertNotEquals(
            EpubChecksum.of("version-1".toByteArray()),
            EpubChecksum.of("version-2".toByteArray()),
        )
    }

    @Test
    fun `the checksum is a stable hex SHA-256`() {
        // SHA-256 of the empty input, well-known vector.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            EpubChecksum.of(ByteArray(0)),
        )
    }
}
