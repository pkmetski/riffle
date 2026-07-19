package com.riffle.core.data.absbookmark

import com.riffle.core.data.absbookmark.AbsBookmarkChunkCodec.ReadBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsBookmarkChunkCodecTest {

    private val deviceA = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val deviceB = "d4a2c5e7-1b7f-4d3e-8c1a-2b6f4d3e5c1a"

    @Test
    fun `deviceIdx is stable and within range`() {
        val a = AbsBookmarkChunkCodec.deviceIdx(deviceA)
        val b = AbsBookmarkChunkCodec.deviceIdx(deviceB)
        assertTrue(a in 0 until AbsBookmarkChunkCodec.MAX_DEVICES)
        assertTrue(b in 0 until AbsBookmarkChunkCodec.MAX_DEVICES)
        assertNotEquals(a, b)
        assertEquals(a, AbsBookmarkChunkCodec.deviceIdx(deviceA))
    }

    @Test
    fun `deviceShort is 8 hex chars`() {
        val s = AbsBookmarkChunkCodec.deviceShort(deviceA)
        assertEquals(8, s.length)
        assertTrue(s.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `timeSlot always lands in the reserved negative range`() {
        val t = AbsBookmarkChunkCodec.timeSlot(0, 0)
        assertEquals(AbsBookmarkChunkCodec.TIME_BASE, t)
        val tMax = AbsBookmarkChunkCodec.timeSlot(
            AbsBookmarkChunkCodec.MAX_DEVICES - 1,
            AbsBookmarkChunkCodec.CHUNKS_PER_DEVICE - 1,
        )
        assertTrue(tMax < AbsBookmarkChunkCodec.TIME_BASE)
    }

    @Test
    fun `parseTimeSlot inverts timeSlot`() {
        val idx = 12_345
        val chunk = 42
        val t = AbsBookmarkChunkCodec.timeSlot(idx, chunk)
        val slot = AbsBookmarkChunkCodec.parseTimeSlot(t)!!
        assertEquals(idx, slot.deviceIdx)
        assertEquals(chunk, slot.chunkIdx)
    }

    @Test
    fun `parseTimeSlot rejects real audio positions`() {
        assertNull(AbsBookmarkChunkCodec.parseTimeSlot(0))
        assertNull(AbsBookmarkChunkCodec.parseTimeSlot(300))
        assertNull(AbsBookmarkChunkCodec.parseTimeSlot(-1)) // yaabsa
        assertNull(AbsBookmarkChunkCodec.parseTimeSlot(AbsBookmarkChunkCodec.TIME_BASE + 1))
    }

    @Test
    fun `parseTitle rejects foreign titles`() {
        assertNull(AbsBookmarkChunkCodec.parseTitle("hello"))
        assertNull(AbsBookmarkChunkCodec.parseTitle("riffle:v99:abcdefab:0:12345678:AAAA"))
        assertNull(AbsBookmarkChunkCodec.parseTitle("riffle:v1:short:0:12345678:AAAA"))
        assertNull(AbsBookmarkChunkCodec.parseTitle("riffle:v1:abcdefab:9999:12345678:AAAA"))
        assertNull(AbsBookmarkChunkCodec.parseTitle("riffle:v1:abcdefab:0:short:AAAA"))
        // yaabsa-style titles must not parse — they're plain JSON at time=-1.
        assertNull(AbsBookmarkChunkCodec.parseTitle("""[{"cfi":"…","color":"#FFEB3B","type":"highlight"}]"""))
    }

    @Test
    fun `formatTitle round-trips with parseTitle`() {
        val p = AbsBookmarkChunkCodec.ParsedTitle(
            deviceShort = "abcdef01",
            chunkIdx = 7,
            contentHashPrefix = "12345678",
            payloadB64 = "AAAAAA",
        )
        assertEquals(p, AbsBookmarkChunkCodec.parseTitle(AbsBookmarkChunkCodec.formatTitle(p)))
    }

    @Test
    fun `encode then decodeShard round-trips a small payload`() {
        val payload = """[{"id":"urn:x:1","type":"Annotation","target":"epubcfi(/6/4)"}]"""
        val wire = AbsBookmarkChunkCodec.encode(deviceA, payload, nowEpochMs = 1_700_000_000_000L)
        // At least manifest + one payload chunk.
        assertTrue(wire.size >= 2)
        val reads = wire.map { ReadBookmark(it.time, it.title) }
        val decoded = AbsBookmarkChunkCodec.decodeShard(AbsBookmarkChunkCodec.deviceShort(deviceA), reads)!!
        assertEquals(payload, decoded.payload)
        assertEquals(1, decoded.manifest.version)
        assertEquals("gzip+b64", decoded.manifest.encoding)
    }

    @Test
    fun `encode then decodeShard round-trips a large payload across many chunks`() {
        // Simulate the user's heavy figure book (~1 MB uncompressed JSON with base64 blobs).
        // Use a mix of high-entropy (base64-ish) + repeated JSON so gzip is representative.
        val entropy = buildString {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            var seed = 0x1234567890ABCDEFL
            repeat(600_000) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                append(alphabet[((seed ushr 33).toInt() and 63)])
            }
        }
        val payload = """[{"id":"urn:x:1","body":"$entropy"}]"""
        val wire = AbsBookmarkChunkCodec.encode(deviceA, payload, nowEpochMs = 1_700_000_000_000L)
        assertTrue("expected multiple chunks, got ${wire.size}", wire.size > 3)
        val reads = wire.map { ReadBookmark(it.time, it.title) }
        val decoded = AbsBookmarkChunkCodec.decodeShard(AbsBookmarkChunkCodec.deviceShort(deviceA), reads)!!
        assertEquals(payload, decoded.payload)
    }

    @Test
    fun `decodeShard returns null when a payload chunk is missing`() {
        // High-entropy payload that gzip can't collapse — forces multi-chunk output.
        val payload = highEntropyPayload(400_000)
        val wire = AbsBookmarkChunkCodec.encode(deviceA, payload, nowEpochMs = 1L)
        assertTrue("expected multi-chunk, got ${wire.size}", wire.size >= 3)
        val truncated = wire.dropLast(1).map { ReadBookmark(it.time, it.title) }
        assertNull(AbsBookmarkChunkCodec.decodeShard(AbsBookmarkChunkCodec.deviceShort(deviceA), truncated))
    }

    private fun highEntropyPayload(size: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        var seed = 0x1234567890ABCDEFL
        return buildString(size) {
            repeat(size) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                append(alphabet[((seed ushr 33).toInt() and 63)])
            }
        }
    }

    @Test
    fun `decodeShard returns null when manifest is missing`() {
        val payload = """[{"id":"urn:x:1"}]"""
        val wire = AbsBookmarkChunkCodec.encode(deviceA, payload, nowEpochMs = 1L)
        val withoutManifest = wire.drop(1).map { ReadBookmark(it.time, it.title) }
        assertNull(AbsBookmarkChunkCodec.decodeShard(AbsBookmarkChunkCodec.deviceShort(deviceA), withoutManifest))
    }

    @Test
    fun `decodeShard returns null when hash does not match tampered chunk`() {
        val payload = """[{"id":"urn:x:1","body":"content"}]"""
        val wire = AbsBookmarkChunkCodec.encode(deviceA, payload, nowEpochMs = 1L).toMutableList()
        // Tamper with the payload chunk: rewrite payload-b64 to something else valid-shaped.
        val bad = wire[1]
        val parsed = AbsBookmarkChunkCodec.parseTitle(bad.title)!!
        val tamperedTitle = AbsBookmarkChunkCodec.formatTitle(
            parsed.copy(payloadB64 = "AAAAAAAA"),
        )
        wire[1] = AbsBookmarkChunkCodec.WireChunk(bad.time, tamperedTitle)
        val reads = wire.map { ReadBookmark(it.time, it.title) }
        assertNull(AbsBookmarkChunkCodec.decodeShard(AbsBookmarkChunkCodec.deviceShort(deviceA), reads))
    }

    @Test
    fun `decodeShard filters out other devices' chunks and yaabsa titles`() {
        val payloadA = """[{"id":"urn:x:A"}]"""
        val payloadB = """[{"id":"urn:x:B"}]"""
        val wireA = AbsBookmarkChunkCodec.encode(deviceA, payloadA, 1L)
        val wireB = AbsBookmarkChunkCodec.encode(deviceB, payloadB, 1L)
        val mixed = (wireA + wireB).map { ReadBookmark(it.time, it.title) } +
            // yaabsa noise: time=-1 with a plain-JSON title.
            ReadBookmark(-1, """[{"cfi":"epubcfi(/6/4)","color":"#FFEB3B","type":"highlight"}]""") +
            // real audio bookmark noise: positive time.
            ReadBookmark(300, "Nice quote")
        val decodedA = AbsBookmarkChunkCodec.decodeShard(AbsBookmarkChunkCodec.deviceShort(deviceA), mixed)!!
        val decodedB = AbsBookmarkChunkCodec.decodeShard(AbsBookmarkChunkCodec.deviceShort(deviceB), mixed)!!
        assertEquals(payloadA, decodedA.payload)
        assertEquals(payloadB, decodedB.payload)
    }

    @Test
    fun `every emitted title stays under the 48KB cap`() {
        val payload = buildString { repeat(5_000_000) { append(('!'.code + (it % 90)).toChar()) } }
        val wire = AbsBookmarkChunkCodec.encode(deviceA, payload, 1L)
        for (c in wire) {
            assertTrue(
                "title bytes=${c.title.length} exceeds ${AbsBookmarkChunkCodec.MAX_TITLE_BYTES}",
                c.title.length <= AbsBookmarkChunkCodec.MAX_TITLE_BYTES,
            )
        }
    }

    @Test
    fun `manifest is always the first emitted chunk`() {
        val payload = """[{"id":"urn:x:1"}]"""
        val wire = AbsBookmarkChunkCodec.encode(deviceA, payload, 1L)
        val parsed = AbsBookmarkChunkCodec.parseTitle(wire.first().title)!!
        assertEquals(AbsBookmarkChunkCodec.MANIFEST_CHUNK_IDX, parsed.chunkIdx)
    }

    @Test
    fun `emitted times are dense and in the reserved range for one device`() {
        val payload = "x".repeat(300_000)
        val wire = AbsBookmarkChunkCodec.encode(deviceA, payload, 1L)
        val slots = wire.map { AbsBookmarkChunkCodec.parseTimeSlot(it.time)!! }
        val idxs = slots.map { it.deviceIdx }.toSet()
        assertEquals("all chunks share one deviceIdx", 1, idxs.size)
        val chunkIndexes = slots.map { it.chunkIdx }.sorted()
        assertEquals((0 until chunkIndexes.size).toList(), chunkIndexes)
    }
}
