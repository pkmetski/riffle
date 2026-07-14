package com.riffle.core.catalog.chitanka

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Thin OkHttp wrapper for Chitanka/Gramofonche HTML fetches.
 *
 * Honors upstream 429 rate-limiting with the same schedule as the reference
 * TypeScript scraper: 3 attempts, backing off 1.5s then 3s. Identifies itself
 * with a stable [userAgent] so the origin can throttle or block cleanly if it
 * needs to. Every other 4xx/5xx propagates as [ChitankaHttpException] without
 * retry.
 *
 * Percent-encoding of Cyrillic paths is done at the [ChitankaScraper.toAbsolute]
 * layer (via `java.net.URI`); by the time a URL reaches this client it's already
 * ASCII-safe.
 */
class ChitankaHttpClient(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {

    /**
     * GET [url] and return the response body as a String. Retries on HTTP 429 up to
     * [retryDelaysMs].size + 1 total attempts.
     */
    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "bg,en;q=0.5")
                .build()
            val (status, body, msg) = client.newCall(request).execute().use { r ->
                Triple(r.code, if (r.isSuccessful) r.body.string() else null, r.message)
            }
            if (status == 429 && attempt < retryDelaysMs.size) {
                delay(retryDelaysMs[attempt])
                attempt++
                continue
            }
            if (body == null) throw ChitankaHttpException(code = status, url = url, message = msg)
            return@withContext body
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** True when [url] responds 2xx to a HEAD request. Used by [connectivityCheck]. */
    suspend fun ping(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .head()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Content-Length reported by the origin on a HEAD, or `null` on non-2xx / network error.
     * Used as a fallback in the audiobook capability when [probeMp3DurationSec] can't derive
     * an exact duration from the MP3 headers.
     */
    suspend fun headContentLength(url: String): Long? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .head()
                .build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                r.header("Content-Length")?.toLongOrNull()
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Derives the exact duration of an MP3 at [url] in seconds by:
     *
     * 1. Fetching the first 10 bytes with a Range GET, reading the total size from the
     *    `Content-Range` header, and — if the payload starts with an ID3v2 tag — computing the
     *    audio offset from the tag's syncsafe size field. Gramofonche embeds ~200 KiB of cover
     *    art in every track, so a single sniff-window range at offset 0 never sees an audio
     *    frame; the two-stage fetch anchors the second read at the true audio start.
     * 2. Fetching [MP3_PROBE_BYTES] starting at the audio offset, locating the first Layer III
     *    frame, and — when a Xing/Info tag is present in the frame's side-info area — reading
     *    the exact frame count. Duration is then `frames * samplesPerFrame / sampleRateHz`,
     *    which is what LAME encodes for VBR files (Gramofonche's rips have Xing headers with
     *    a lying frame-header bitrate but truthful frame counts).
     * 3. Falling back to CBR math (`audioBytes * 8 / bitrateBps`) when there is no Xing/Info
     *    tag but the frame header alone is enough.
     *
     * Returns `null` on network failure, non-2xx/206 responses, missing `Content-Range` on
     * the initial fetch, or when no Layer III frame is found in the sniff window. The caller
     * should fall back to a coarser estimate in that case.
     */
    suspend fun probeMp3DurationSec(url: String): Double? = withContext(Dispatchers.IO) {
        try {
            val head = rangeGet(url, 0L, 9L) ?: return@withContext null
            val audioOffset = id3v2AudioOffset(head.bytes) ?: 0
            val end = (audioOffset + MP3_PROBE_BYTES - 1).toLong()
            val audio = rangeGet(url, audioOffset.toLong(), end) ?: return@withContext null
            val frame = findFirstMp3Frame(audio.bytes) ?: return@withContext null
            val xingFrames = parseXingFrameCount(audio.bytes, frame.frameStart, frame.meta)
            if (xingFrames != null && xingFrames > 0) {
                xingFrames.toDouble() * frame.meta.samplesPerFrame / frame.meta.sampleRateHz
            } else {
                val audioBytes = head.totalBytes - audioOffset
                if (audioBytes <= 0) null else audioBytes.toDouble() * 8.0 / frame.meta.bitrateBps
            }
        } catch (_: IOException) {
            null
        }
    }

    /** A byte payload alongside the total resource size read from `Content-Range`. */
    private data class RangeReply(val bytes: ByteArray, val totalBytes: Long)

    private fun rangeGet(url: String, start: Long, endInclusive: Long): RangeReply? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=$start-$endInclusive")
            .build()
        return client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return@use null
            // Content-Range: bytes 0-9/12161710
            val total = r.header("Content-Range")?.substringAfter("/", "")?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()
                ?: return@use null
            RangeReply(r.body.bytes(), total)
        }
    }

    companion object {
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1_500L, 3_000L)

        /**
         * Sniff window for [probeMp3DurationSec]'s second fetch. 16 KiB starting at the audio
         * offset comfortably covers the first frame header + side info + Xing/Info tag with
         * its TOC, for any layer/channel-mode/sample-rate combination we care about.
         */
        internal const val MP3_PROBE_BYTES: Int = 16 * 1024

        // MPEG audio bitrate tables for Layer III, in kbps, indexed by the 4-bit bitrate index
        // (0 = free, 15 = reserved — both rejected by the parser).
        // Reference: ISO/IEC 11172-3 / 13818-3.
        private val MP3_V1_L3_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        private val MP3_V2_L3_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

        internal data class Mp3FrameMeta(
            val versionBits: Int,     // 0=MPEG-2.5, 2=MPEG-2, 3=MPEG-1
            val channelMode: Int,     // 0=Stereo, 1=Joint, 2=Dual, 3=Mono
            val sampleRateHz: Int,
            val samplesPerFrame: Int, // 1152 for MPEG-1 L3; 576 for MPEG-2/2.5 L3
            val bitrateBps: Int,      // as declared by the frame header
        )

        internal data class Mp3Frame(val frameStart: Int, val meta: Mp3FrameMeta)

        /**
         * Returns the byte offset at which audio data starts if [bytes] begins with an ID3v2
         * header, or `null` when there is no ID3v2 tag (in which case the caller should treat
         * the audio as starting at offset 0). Reads only the 10-byte header; safe on partial
         * buffers.
         */
        internal fun id3v2AudioOffset(bytes: ByteArray): Int? {
            if (bytes.size < 10) return null
            if (bytes[0] != 'I'.code.toByte() ||
                bytes[1] != 'D'.code.toByte() ||
                bytes[2] != '3'.code.toByte()
            ) return null
            val size = ((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)
            return 10 + size
        }

        /**
         * Scans [bytes] from [startOffset] for the first valid MPEG Layer III frame sync and
         * returns its offset plus decoded header metadata. Rejects reserved version, non-Layer-III
         * layer, and reserved bitrate/sample-rate indexes.
         */
        internal fun findFirstMp3Frame(bytes: ByteArray, startOffset: Int = 0): Mp3Frame? {
            var i = startOffset.coerceAtLeast(0)
            while (i <= bytes.size - 4) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                    val versionBits = (b1 shr 3) and 0x03  // 00=v2.5 01=reserved 10=v2 11=v1
                    val layerBits = (b1 shr 1) and 0x03    // 00=reserved 01=III 10=II 11=I
                    if (versionBits != 1 && layerBits == 1) {
                        val b2 = bytes[i + 2].toInt() and 0xFF
                        val bitrateIdx = (b2 shr 4) and 0x0F
                        val sampleRateIdx = (b2 shr 2) and 0x03
                        if (bitrateIdx in 1..14 && sampleRateIdx != 3) {
                            val b3 = bytes[i + 3].toInt() and 0xFF
                            val channelMode = (b3 shr 6) and 0x03
                            val bitrateKbps = if (versionBits == 3) {
                                MP3_V1_L3_BITRATES[bitrateIdx]
                            } else {
                                MP3_V2_L3_BITRATES[bitrateIdx]
                            }
                            val sampleRate = sampleRateHz(versionBits, sampleRateIdx)
                            val samplesPerFrame = if (versionBits == 3) 1152 else 576
                            return Mp3Frame(
                                frameStart = i,
                                meta = Mp3FrameMeta(
                                    versionBits = versionBits,
                                    channelMode = channelMode,
                                    sampleRateHz = sampleRate,
                                    samplesPerFrame = samplesPerFrame,
                                    bitrateBps = bitrateKbps * 1000,
                                ),
                            )
                        }
                    }
                }
                i++
            }
            return null
        }

        /**
         * Legacy CBR-bitrate probe retained for tests and any caller that only wants the raw
         * frame-header bitrate. New code should use [findFirstMp3Frame] which also exposes
         * the channel mode / sample rate needed to locate a Xing/Info tag.
         */
        internal fun parseMp3BitrateBps(bytes: ByteArray): Int? {
            val audioOffset = id3v2AudioOffset(bytes) ?: 0
            return findFirstMp3Frame(bytes, audioOffset)?.meta?.bitrateBps
        }

        /**
         * Locates a Xing (VBR) or Info (CBR) tag inside the first frame's side-info area and
         * returns the encoded total frame count, or `null` when no tag is present, the tag
         * doesn't advertise a frame count, or the buffer is too short. `frames` in Xing tags is
         * authoritative even for CBR files — the frame-header bitrate is often a placeholder
         * for LAME-encoded VBR streams (Gramofonche's rips are exactly this shape).
         */
        internal fun parseXingFrameCount(bytes: ByteArray, frameStart: Int, meta: Mp3FrameMeta): Int? {
            val sideInfoBytes = when {
                meta.versionBits == 3 && meta.channelMode == 3 -> 17 // MPEG-1 mono
                meta.versionBits == 3 -> 32                         // MPEG-1 stereo/joint/dual
                meta.channelMode == 3 -> 9                          // MPEG-2/2.5 mono
                else -> 17                                          // MPEG-2/2.5 stereo/joint/dual
            }
            val tagOffset = frameStart + 4 + sideInfoBytes
            if (tagOffset + 8 > bytes.size) return null
            val tag = String(bytes, tagOffset, 4, Charsets.US_ASCII)
            if (tag != "Xing" && tag != "Info") return null
            val flags = readInt32BE(bytes, tagOffset + 4) ?: return null
            if ((flags and 0x0001) == 0) return null   // no frame-count field
            return readInt32BE(bytes, tagOffset + 8)
        }

        private fun readInt32BE(bytes: ByteArray, offset: Int): Int? {
            if (offset + 4 > bytes.size) return null
            return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        }

        private fun sampleRateHz(versionBits: Int, idx: Int): Int = when (versionBits) {
            3 -> intArrayOf(44100, 48000, 32000)[idx]  // MPEG-1
            2 -> intArrayOf(22050, 24000, 16000)[idx]  // MPEG-2
            0 -> intArrayOf(11025, 12000, 8000)[idx]   // MPEG-2.5
            else -> 0
        }
    }
}

/** Non-retryable HTTP failure. Retryable 429s are handled internally. */
class ChitankaHttpException(
    val code: Int,
    val url: String,
    message: String,
) : IOException("Chitanka HTTP $code for $url: $message")
