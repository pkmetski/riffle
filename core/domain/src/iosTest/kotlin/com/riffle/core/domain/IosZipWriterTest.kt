package com.riffle.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [IosZipWriter] exists so iOS can repackage a Storyteller bundle without its audio (the Readaloud
 * sidecar, ADR 0040). The output has to be a spec-valid ZIP — `java.util.zip` on the Android side
 * rejects a wrong CRC-32 — so these pin the checksum and the reader/writer round-trip.
 */
class IosZipWriterTest {

    @Test
    fun `emits the standard CRC-32 for the known check value`() {
        // "123456789" has the well-known CRC-32 check value 0xCBF43926.
        assertEquals(0xCBF43926u.toInt(), IosZipWriter.crc32("123456789".encodeToByteArray()))
    }

    @Test
    fun `crc32 of empty input is zero`() {
        assertEquals(0, IosZipWriter.crc32(ByteArray(0)))
    }

    @Test
    fun `written entries read back byte-identical`() {
        val entries = listOf(
            "a/one.txt" to "first".encodeToByteArray(),
            "b/two.bin" to ByteArray(300) { (it % 251).toByte() },
        )

        val archive = IosZipArchive(IosZipWriter.write(entries))

        assertEquals(listOf("a/one.txt", "b/two.bin").sorted(), archive.entryNames().sorted())
        assertEquals("first", archive.readEntryAsText("a/one.txt"))
        assertTrue(archive.readEntry("b/two.bin")!!.contentEquals(entries[1].second))
    }

    @Test
    fun `an empty entry round-trips`() {
        val archive = IosZipArchive(IosZipWriter.write(listOf("empty.txt" to ByteArray(0))))

        assertEquals(listOf("empty.txt"), archive.entryNames())
        assertEquals(0, archive.readEntry("empty.txt")?.size)
    }

    @Test
    fun `an empty archive is still readable`() {
        assertEquals(emptyList(), IosZipArchive(IosZipWriter.write(emptyList())).entryNames())
    }
}
