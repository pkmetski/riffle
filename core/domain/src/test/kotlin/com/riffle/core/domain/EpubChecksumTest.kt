package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubChecksumTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `file checksum matches the in-memory checksum of the same bytes`() {
        // The synced bundle is hundreds of MB; the file overload must hash by streaming, never
        // loading the whole file, yet agree byte-for-byte with the in-memory checksum.
        val bytes = ByteArray(200_000) { (it * 31 + 7).toByte() }
        val file = tmp.newFile("book.epub").apply { writeBytes(bytes) }

        assertEquals(EpubChecksum.of(bytes), EpubChecksum.of(file))
    }


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
