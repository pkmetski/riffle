package com.riffle.shared.audiobook

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real iOS bundle extractor over a real zip on the simulator's filesystem — the code
 * path a Storyteller readaloud bundle actually takes before AVQueuePlayer sees it (ADR 0027).
 *
 * [IosAudioPlayerControllerTest] pins that the controller *asks* for extraction; this pins that
 * asking produces openable `file://` URLs whose bytes are the zip entries'.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBundleAudioExtractorTest {

    private val written = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        written.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private val entries = mapOf(
        "OEBPS/audio/part0001.mp4" to "first track bytes".encodeToByteArray(),
        "OEBPS/audio/part0002.mp4" to "second track bytes, longer".encodeToByteArray(),
    )

    @Test
    fun zipEntriesBecomeReadableFileUrls() = runTest {
        val zipPath = writeBundle(entries)
        val urls = IosBundleAudioExtractor().extractedTrackUrls(zipPath, entries.keys.toList())

        assertEquals(2, urls.size, "both entries must resolve")
        urls.forEach { assertTrue(it.startsWith("file://"), "not a file URL: $it") }

        entries.keys.forEachIndexed { index, entry ->
            val path = NSURL.URLWithString(urls[index])?.path
            assertTrue(path != null, "extracted URL has no path: ${urls[index]}")
            assertEquals(
                entries.getValue(entry).decodeToString(),
                readText(path),
                "extracted bytes for $entry must be the entry's own",
            )
        }
    }

    @Test
    fun aMissingEntryIsDroppedRatherThanFabricated() = runTest {
        val zipPath = writeBundle(entries)
        val urls = IosBundleAudioExtractor()
            .extractedTrackUrls(zipPath, entries.keys.toList() + "OEBPS/audio/part0003.mp4")

        assertEquals(
            2,
            urls.size,
            "an entry the bundle does not contain must not yield a URL — the caller uses the " +
                "short count to refuse a session whose spans it could not fill",
        )
    }

    @Test
    fun secondExtractionReusesTheAlreadyWrittenFiles() = runTest {
        val zipPath = writeBundle(entries)
        val extractor = IosBundleAudioExtractor()
        val first = extractor.extractedTrackUrls(zipPath, entries.keys.toList())
        val second = extractor.extractedTrackUrls(zipPath, entries.keys.toList())
        assertEquals(first, second, "re-opening the same bundle must land on the same files")
    }

    @Test
    fun anAbsentBundleYieldsNoUrls() = runTest {
        val urls = IosBundleAudioExtractor()
            .extractedTrackUrls("${NSTemporaryDirectory()}/does-not-exist.epub", entries.keys.toList())
        assertEquals(emptyList(), urls)
    }

    @Test
    fun entryPathsFlattenIntoOneFilenameAndKeepTheExtension() {
        // The extension has to survive: AVFoundation sniffs the container from it, and a
        // file with none is rejected outright.
        assertEquals("OEBPS_audio_part0001.mp4", cacheFileName("OEBPS/audio/part0001.mp4"))
        assertEquals("audio_ch-02.m4b", cacheFileName("/audio/ch-02.m4b"))
        assertEquals("plain", cacheFileName("plain"))
    }

    // ── a minimal STORED (uncompressed) zip, written to the simulator's tmp dir ──

    private fun writeBundle(entries: Map<String, ByteArray>): String {
        val path = "${NSTemporaryDirectory()}/riffle-bundle-test-${Random.nextInt()}.epub"
        writeBytes(path, buildStoredZip(entries))
        written += path
        return path
    }

    private fun buildStoredZip(entries: Map<String, ByteArray>): ByteArray {
        val out = mutableListOf<Byte>()
        val offsets = mutableMapOf<String, Int>()
        for ((name, body) in entries) {
            offsets[name] = out.size
            val nameBytes = name.encodeToByteArray()
            out.int32(LOCAL_HEADER_SIGNATURE)
            out.int16(20) // version needed
            out.int16(0) // flags
            out.int16(0) // method: stored
            out.int16(0) // mod time
            out.int16(0) // mod date
            out.int32(0) // crc32 — IosZipArchive does not verify it
            out.int32(body.size)
            out.int32(body.size)
            out.int16(nameBytes.size)
            out.int16(0) // extra length
            out.addAll(nameBytes.toList())
            out.addAll(body.toList())
        }
        val cdOffset = out.size
        for ((name, body) in entries) {
            val nameBytes = name.encodeToByteArray()
            out.int32(CENTRAL_HEADER_SIGNATURE)
            out.int16(20) // version made by
            out.int16(20) // version needed
            out.int16(0) // flags
            out.int16(0) // method: stored
            out.int16(0) // mod time
            out.int16(0) // mod date
            out.int32(0) // crc32
            out.int32(body.size)
            out.int32(body.size)
            out.int16(nameBytes.size)
            out.int16(0) // extra length
            out.int16(0) // comment length
            out.int16(0) // disk number start
            out.int16(0) // internal attributes
            out.int32(0) // external attributes
            out.int32(offsets.getValue(name))
            out.addAll(nameBytes.toList())
        }
        val cdSize = out.size - cdOffset
        out.int32(EOCD_SIGNATURE)
        out.int16(0) // this disk
        out.int16(0) // disk with central directory
        out.int16(entries.size)
        out.int16(entries.size)
        out.int32(cdSize)
        out.int32(cdOffset)
        out.int16(0) // comment length
        return out.toByteArray()
    }

    private fun MutableList<Byte>.int16(value: Int) {
        add((value and 0xFF).toByte())
        add(((value shr 8) and 0xFF).toByte())
    }

    private fun MutableList<Byte>.int32(value: Int) {
        add((value and 0xFF).toByte())
        add(((value shr 8) and 0xFF).toByte())
        add(((value shr 16) and 0xFF).toByte())
        add(((value shr 24) and 0xFF).toByte())
    }

    private fun writeBytes(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        data.writeToFile(path, atomically = true)
    }

    private fun readText(path: String?): String {
        val data = NSData.dataWithContentsOfFile(path ?: return "") ?: return ""
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) {
            out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
        return out.decodeToString()
    }

    private companion object {
        const val LOCAL_HEADER_SIGNATURE = 0x04034B50
        const val CENTRAL_HEADER_SIGNATURE = 0x02014B50
        const val EOCD_SIGNATURE = 0x06054B50
    }
}
