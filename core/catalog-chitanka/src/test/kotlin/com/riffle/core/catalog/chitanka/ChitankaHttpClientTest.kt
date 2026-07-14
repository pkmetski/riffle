package com.riffle.core.catalog.chitanka

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ChitankaHttpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient() = ChitankaHttpClient(
        client = OkHttpClient(),
        userAgent = "Riffle/test",
        retryDelaysMs = listOf(0L, 0L),  // no wait in tests
    )

    @Test
    fun `succeeds on first 200`() = runTest {
        server.enqueue(MockResponse().setBody("hello"))
        val http = newClient()
        val body = http.getString(server.url("/x").toString())
        assertEquals("hello", body)
    }

    @Test
    fun `retries once on 429 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("world"))
        val http = newClient()
        val body = http.getString(server.url("/x").toString())
        assertEquals("world", body)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `throws after three 429s`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429)) }
        val http = newClient()
        try {
            http.getString(server.url("/x").toString())
            fail("expected ChitankaHttpException")
        } catch (e: ChitankaHttpException) {
            assertEquals(429, e.code)
        }
    }

    @Test
    fun `non-429 error is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val http = newClient()
        try {
            http.getString(server.url("/x").toString())
            fail("expected exception")
        } catch (e: ChitankaHttpException) {
            assertEquals(500, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `user-agent is sent`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        val http = newClient()
        http.getString(server.url("/x").toString())
        val ua = server.takeRequest().getHeader("User-Agent")
        assertTrue("expected Riffle UA, got $ua", ua?.startsWith("Riffle/") == true)
    }

    @Test
    fun `ping returns true on 200 and false on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(404))
        val http = newClient()
        assertTrue(http.ping(server.url("/x").toString()))
        assertTrue(!http.ping(server.url("/y").toString()))
    }

    @Test
    fun `parseMp3BitrateBps reads MPEG-1 Layer III 128 kbps frame header`() {
        // MPEG-1 (versionBits=11), Layer III (layerBits=01), bitrate index 9 → 128 kbps.
        // Byte 1: 1111 1011 = 0xFB. Byte 2: 1001 0000 = 0x90 (bitrateIdx 9).
        val bytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        assertEquals(128_000, ChitankaHttpClient.parseMp3BitrateBps(bytes))
    }

    @Test
    fun `parseMp3BitrateBps reads MPEG-2 Layer III 64 kbps frame header`() {
        // MPEG-2 (versionBits=10), Layer III (layerBits=01), bitrate index 8 → 64 kbps.
        // Byte 1: 1111 0011 = 0xF3. Byte 2: 1000 0000 = 0x80 (bitrateIdx 8).
        val bytes = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x80.toByte(), 0x00)
        assertEquals(64_000, ChitankaHttpClient.parseMp3BitrateBps(bytes))
    }

    @Test
    fun `parseMp3BitrateBps skips ID3v2 tag and finds later frame`() {
        // ID3v2 header + 20 bytes of tag payload, then a MPEG-1 L3 96 kbps frame.
        // syncsafe size = 20 → bytes[6..9] = 0,0,0,20 (0x14).
        val id3 = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x14,
        )
        val payload = ByteArray(20) { 0x00 }
        // bitrate idx 7 for MPEG-1 L3 → 96 kbps. Byte 2: 0111 0000 = 0x70.
        val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x70.toByte(), 0x00)
        val bytes = id3 + payload + frame
        assertEquals(96_000, ChitankaHttpClient.parseMp3BitrateBps(bytes))
    }

    @Test
    fun `parseMp3BitrateBps returns null when no sync found`() {
        val bytes = ByteArray(64) { 0x00 }
        assertEquals(null, ChitankaHttpClient.parseMp3BitrateBps(bytes))
    }

    @Test
    fun `parseMp3BitrateBps rejects reserved bitrate index`() {
        // bitrate index 15 (reserved). Byte 2: 1111 0000 = 0xF0.
        val bytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xF0.toByte(), 0x00)
        assertEquals(null, ChitankaHttpClient.parseMp3BitrateBps(bytes))
    }

    @Test
    fun `parseMp3BitrateBps rejects Layer I (non-MP3)`() {
        // Layer I (layerBits=11). Byte 1: 1111 1111 = 0xFF. But 0xFF trips the sync scan too;
        // use MPEG-1 (versionBits=11) + Layer I (11): 1111 1111 -> byte1 = 0xFF is same as sync.
        // Correct: version=11, layer=11 -> byte1 bits: 111 11 11 1 = 0xFF. Ambiguous; skip.
        // Use Layer II instead (layer=10): 111 11 10 1 = 0xFD.
        val bytes = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x90.toByte(), 0x00)
        assertEquals(null, ChitankaHttpClient.parseMp3BitrateBps(bytes))
    }

    @Test
    fun `id3v2AudioOffset reads syncsafe size field`() {
        // Real Gramofonche size: bytes[6..9] = 00 0c 71 43 → syncsafe = 211,139
        // → audio at 10 + 211,139 = 211,149.
        val hdr = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            0x03, 0x00, 0x00, 0x00, 0x0c, 0x71, 0x43,
        )
        assertEquals(211_149, ChitankaHttpClient.id3v2AudioOffset(hdr))
    }

    @Test
    fun `id3v2AudioOffset returns null when no ID3 signature`() {
        val hdr = ByteArray(10) { 0x00 }
        assertEquals(null, ChitankaHttpClient.id3v2AudioOffset(hdr))
    }

    @Test
    fun `findFirstMp3Frame decodes MPEG-1 Layer III 128 kbps mono 44100 Hz`() {
        // Real Gramofonche first frame: FF FB 90 C4 → v1, L3, 128 kbps, 44100 Hz, mono.
        val bytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0xC4.toByte())
        val f = ChitankaHttpClient.findFirstMp3Frame(bytes)!!
        assertEquals(0, f.frameStart)
        assertEquals(128_000, f.meta.bitrateBps)
        assertEquals(44_100, f.meta.sampleRateHz)
        assertEquals(1152, f.meta.samplesPerFrame)
        assertEquals(3, f.meta.channelMode) // Mono
    }

    @Test
    fun `findFirstMp3Frame skips false sync bytes and finds later frame`() {
        val noise = ByteArray(5) { 0xFF.toByte() }  // 0xFF FF FF FF FF — no valid header follows
        val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0xC4.toByte())
        val f = ChitankaHttpClient.findFirstMp3Frame(noise + frame)!!
        assertEquals(5, f.frameStart)
    }

    @Test
    fun `findFirstMp3Frame rejects reserved bitrate index`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xF0.toByte(), 0x00)
        assertEquals(null, ChitankaHttpClient.findFirstMp3Frame(bytes))
    }

    @Test
    fun `findFirstMp3Frame rejects Layer II`() {
        // Layer II (layerBits=10). Byte 1 = 1111 1101 = 0xFD.
        val bytes = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x90.toByte(), 0x00)
        assertEquals(null, ChitankaHttpClient.findFirstMp3Frame(bytes))
    }

    @Test
    fun `parseXingFrameCount reads real Gramofonche Xing header`() {
        // Exact bytes from https://gramofonche.chitanka.info/prikazki/…/barabanchik--baa1831.mp3
        // at audio offset 211,149. Xing frame count = 0x0000CA4F = 51791.
        val real = byteArrayOf(
            0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0xC4.toByte(),  // frame header (v1 L3 mono)
        ) + ByteArray(17) { 0x00 } +                                       // 17-byte side info for mono
            byteArrayOf(
                'X'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(),
                0x00, 0x00, 0x00, 0x0F,          // flags: frames + bytes + toc + quality
                0x00, 0x00, 0xCA.toByte(), 0x4F, // frame count = 51791
                0x00, 0xB6.toByte(), 0x59, 0x61, // byte count = 11,950,433
            )
        val f = ChitankaHttpClient.findFirstMp3Frame(real)!!
        assertEquals(51_791, ChitankaHttpClient.parseXingFrameCount(real, f.frameStart, f.meta))
        val durationSec = 51_791.0 * f.meta.samplesPerFrame / f.meta.sampleRateHz
        // 51791 * 1152 / 44100 ≈ 1352.91 s ≈ 22m 32.9s (matches the 22:32 visible in the player).
        assertEquals(1352.91, durationSec, 0.05)
    }

    @Test
    fun `parseXingFrameCount uses 32-byte side info for MPEG-1 stereo`() {
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) // v1 L3 stereo
        val bytes = header + ByteArray(32) { 0x00 } + byteArrayOf(
            'X'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(),
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x03, 0xE8.toByte(),  // 1000 frames
        )
        val f = ChitankaHttpClient.findFirstMp3Frame(bytes)!!
        assertEquals(0, f.meta.channelMode) // Stereo
        assertEquals(1000, ChitankaHttpClient.parseXingFrameCount(bytes, f.frameStart, f.meta))
    }

    @Test
    fun `parseXingFrameCount accepts Info tag alongside Xing`() {
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0xC4.toByte())
        val bytes = header + ByteArray(17) { 0x00 } + byteArrayOf(
            'I'.code.toByte(), 'n'.code.toByte(), 'f'.code.toByte(), 'o'.code.toByte(),
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x64,  // 100 frames
        )
        val f = ChitankaHttpClient.findFirstMp3Frame(bytes)!!
        assertEquals(100, ChitankaHttpClient.parseXingFrameCount(bytes, f.frameStart, f.meta))
    }

    @Test
    fun `parseXingFrameCount returns null when frames flag is unset`() {
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0xC4.toByte())
        val bytes = header + ByteArray(17) { 0x00 } + byteArrayOf(
            'X'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(),
            0x00, 0x00, 0x00, 0x02,  // bytes flag only, no frames
            0x00, 0x00, 0x27, 0x10,
        )
        val f = ChitankaHttpClient.findFirstMp3Frame(bytes)!!
        assertEquals(null, ChitankaHttpClient.parseXingFrameCount(bytes, f.frameStart, f.meta))
    }

    @Test
    fun `probeMp3DurationSec issues two Range GETs and returns Xing-derived duration`() = runTest {
        // Stage 1: 10-byte ID3 header with syncsafe size 30 → audio starts at offset 40.
        val id3 = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1E,   // syncsafe size = 30
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-9/1000000")
                .setBody(Buffer().write(id3)),
        )
        // Stage 2: audio payload — MPEG-1 L3 mono frame + Xing with 100 frames.
        val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0xC4.toByte())
        val audio = frame + ByteArray(17) { 0x00 } + byteArrayOf(
            'X'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(),
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x64,  // 100 frames
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 40-16423/1000000")
                .setBody(Buffer().write(audio)),
        )
        val http = newClient()
        val dur = http.probeMp3DurationSec(server.url("/track.mp3").toString())
        // 100 * 1152 / 44100 = 2.6122...
        assertEquals(2.6122, dur!!, 0.001)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        assertEquals("bytes=0-9", first.getHeader("Range"))
        val second = server.takeRequest()
        assertEquals("bytes=40-${40 + ChitankaHttpClient.MP3_PROBE_BYTES - 1}", second.getHeader("Range"))
    }

    @Test
    fun `probeMp3DurationSec falls back to CBR math when no Xing tag`() = runTest {
        // No ID3 tag; frame at offset 0; total size 128000 bytes @ 128 kbps CBR → 8s.
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-9/128000")
                .setBody(Buffer().write(ByteArray(10) { 0x00 })),
        )
        val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0xC4.toByte())
        // padded so no Xing tag matches at the expected offset (17 bytes of side info)
        val audio = frame + ByteArray(200) { 0x00 }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-16383/128000")
                .setBody(Buffer().write(audio)),
        )
        val http = newClient()
        val dur = http.probeMp3DurationSec(server.url("/track.mp3").toString())
        // audioBytes = 128000 - 0 = 128000, / 16000 bytes/sec = 8s
        assertEquals(8.0, dur!!, 0.001)
    }

    @Test
    fun `probeMp3DurationSec returns null on 5xx first fetch`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val http = newClient()
        assertEquals(null, http.probeMp3DurationSec(server.url("/track.mp3").toString()))
    }
}
