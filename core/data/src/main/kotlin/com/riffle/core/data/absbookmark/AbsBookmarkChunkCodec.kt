package com.riffle.core.data.absbookmark

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Encodes / decodes Riffle's ABS-bookmark annotation shards. See
 * `docs/superpowers/specs/2026-07-19-abs-bookmarks-annotation-sync-design.md`.
 *
 * Storage layout: each device writes a "shard" — a manifest bookmark (chunkIdx=0) plus N payload
 * bookmarks (chunkIdx=1..N). Bookmarks live at a reserved negative `time` derived from
 * `(deviceIdx, chunkIdx)`. Titles carry a `riffle:v1:` header followed by a `base64(gzip(json))`
 * payload. Manifest holds the chunk count, encoding, and a content hash so torn writes are
 * detectable and readers can reject partial shards cleanly.
 *
 * Pure JVM: no Android, no coroutines, no network. Suitable for `core:annotations` when that
 * module lands (ADR 0041 platform-agnostic-core rule).
 */
object AbsBookmarkChunkCodec {

    const val VERSION = 1
    const val TITLE_PREFIX = "riffle:v"

    /**
     * Riffle's reserved negative-time range starts at [TIME_BASE] and extends toward `Int.MIN_VALUE`.
     * Real audio bookmarks live at `time ≥ 0`; yaabsa uses `time = -1`. Chosen so real audio
     * bookmarks and yaabsa never collide with us regardless of chunk count.
     */
    const val TIME_BASE: Int = -1_000_000_000

    /**
     * `MAX_DEVICES * CHUNKS_PER_DEVICE ≤ Int.MAX_VALUE - |TIME_BASE|` — 10^6 devices × 1024 chunks
     * fits inside the signed-Int range below [TIME_BASE].
     */
    const val MAX_DEVICES: Int = 1_000_000
    const val CHUNKS_PER_DEVICE: Int = 1024
    const val MANIFEST_CHUNK_IDX: Int = 0
    const val DEVICE_META_CHUNK_IDX: Int = CHUNKS_PER_DEVICE - 1
    const val MAX_TITLE_BYTES: Int = 48 * 1024

    private const val DEVICE_SHORT_LEN = 8
    private const val HASH_PREFIX_LEN = 8

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Assign a device UUID to a stable slot in `[0, MAX_DEVICES)` via SHA-256. Deterministic across
     * platforms, so two devices with the same UUID (they shouldn't exist) would collide at the
     * same slot — collision detection is the read-side's job via `deviceShort` in the title.
     */
    fun deviceIdx(deviceId: String): Int {
        val digest = sha256(deviceId.encodeToByteArray())
        // First 5 bytes → unsigned long → mod MAX_DEVICES.
        var v = 0L
        for (i in 0 until 5) v = (v shl 8) or (digest[i].toLong() and 0xFF)
        return (v % MAX_DEVICES).toInt()
    }

    /** First 8 hex chars of SHA-256(deviceId). Embedded in every title as a human-readable owner tag. */
    fun deviceShort(deviceId: String): String =
        sha256(deviceId.encodeToByteArray()).toHex().take(DEVICE_SHORT_LEN)

    /** Reserved `time` for `(deviceIdx, chunkIdx)`. Always ≤ [TIME_BASE]. */
    fun timeSlot(deviceIdx: Int, chunkIdx: Int): Int {
        require(deviceIdx in 0 until MAX_DEVICES) { "deviceIdx out of range: $deviceIdx" }
        require(chunkIdx in 0 until CHUNKS_PER_DEVICE) { "chunkIdx out of range: $chunkIdx" }
        // (MAX_DEVICES-1) * CHUNKS_PER_DEVICE fits in Int; TIME_BASE - offset stays above Int.MIN_VALUE.
        return TIME_BASE - deviceIdx * CHUNKS_PER_DEVICE - chunkIdx
    }

    /** Inverse of [timeSlot]. Returns null when `time` is not in Riffle's reserved range. */
    fun parseTimeSlot(time: Int): TimeSlot? {
        if (time > TIME_BASE) return null
        val offset = TIME_BASE - time
        val deviceIdx = offset / CHUNKS_PER_DEVICE
        val chunkIdx = offset % CHUNKS_PER_DEVICE
        if (deviceIdx !in 0 until MAX_DEVICES) return null
        return TimeSlot(deviceIdx, chunkIdx)
    }

    /**
     * Parse a bookmark title. Returns null for titles that don't belong to Riffle (wrong prefix,
     * unknown version, malformed header). Never throws — the read pipeline expects to silently
     * skip foreign titles.
     */
    fun parseTitle(title: String): ParsedTitle? {
        if (!title.startsWith(TITLE_PREFIX)) return null
        // riffle:v<n>:<deviceShort>:<chunkIdx>:<hashPrefix>:<payload>
        val parts = title.split(':', limit = 6)
        if (parts.size < 6) return null
        if (parts[0] != "riffle") return null
        val version = parts[1].removePrefix("v").toIntOrNull() ?: return null
        if (version != VERSION) return null
        val deviceShort = parts[2]
        if (deviceShort.length != DEVICE_SHORT_LEN || !deviceShort.all { it.isHexDigit() }) return null
        val chunkIdx = parts[3].toIntOrNull() ?: return null
        if (chunkIdx !in 0 until CHUNKS_PER_DEVICE) return null
        val hashPrefix = parts[4]
        if (hashPrefix.length != HASH_PREFIX_LEN || !hashPrefix.all { it.isHexDigit() }) return null
        return ParsedTitle(deviceShort, chunkIdx, hashPrefix, parts[5])
    }

    /** Serialize [parsed] back to wire form. Round-trip guaranteed with [parseTitle]. */
    fun formatTitle(parsed: ParsedTitle): String =
        "riffle:v$VERSION:${parsed.deviceShort}:${parsed.chunkIdx}:${parsed.contentHashPrefix}:${parsed.payloadB64}"

    /**
     * Split `payload` into wire-ready [WireChunk]s for `deviceId`.
     *
     * **Ordering matters for crash-safety.** Payload chunks come FIRST in the returned list;
     * the manifest is LAST. A caller that uploads chunks in order and crashes mid-flush leaves
     * the OLD manifest referring to the OLD (still-consistent) payload chunks — readers reject
     * partial writes cleanly instead of seeing a manifest that points at newly-changed slots.
     *
     * **Deterministic output.** Same `(deviceId, payload)` produces byte-identical `WireChunk`s
     * — the manifest carries no wall-clock time, so idempotent writes stay idempotent and the
     * write-side diff can skip identical chunks.
     */
    fun encode(deviceId: String, payload: String): List<WireChunk> {
        val deviceIdxV = deviceIdx(deviceId)
        val deviceShortV = deviceShort(deviceId)
        val gzipped = gzip(payload.encodeToByteArray())
        val fullHash = sha256(gzipped).toHex()
        val sliceCap = payloadCapBytes(deviceShortV)
        require(sliceCap > 0) { "cap too small once headers are accounted for" }
        val sliceCount = ((gzipped.size + sliceCap - 1) / sliceCap).coerceAtLeast(1)
        require(sliceCount <= CHUNKS_PER_DEVICE - 1) {
            // -1 because chunk 0 is the manifest.
            "payload requires $sliceCount chunks, exceeds per-device limit ${CHUNKS_PER_DEVICE - 1}"
        }
        val manifest = ManifestPayload(
            version = VERSION,
            chunks = sliceCount,
            encoding = "gzip+b64",
            fullHash = fullHash,
            deviceId = deviceId,
        )
        val out = ArrayList<WireChunk>(sliceCount + 1)
        var offset = 0
        for (i in 0 until sliceCount) {
            val end = minOf(offset + sliceCap, gzipped.size)
            val slice = gzipped.copyOfRange(offset, end)
            out.add(
                wireChunkOf(
                    deviceIdxV = deviceIdxV,
                    deviceShort = deviceShortV,
                    chunkIdx = i + 1,
                    rawContent = slice,
                    // Already gzipped — don't gzip again.
                    gzipContent = false,
                ),
            )
            offset = end
        }
        // Manifest LAST — crash-safety invariant, see kdoc.
        val manifestJson = manifest.toJson()
        out.add(
            wireChunkOf(
                deviceIdxV = deviceIdxV,
                deviceShort = deviceShortV,
                chunkIdx = MANIFEST_CHUNK_IDX,
                rawContent = manifestJson.encodeToByteArray(),
                gzipContent = true,
            ),
        )
        return out
    }

    /**
     * Reassemble one device's shard from the raw bookmarks read off ABS.
     * Chunks with a `deviceShort` different from [ownerDeviceShort] are ignored (belong to another
     * shard). Returns null if any of the following are true — the entire shard is discarded:
     *
     * - Manifest missing or unparseable.
     * - Manifest reports N chunks but fewer than N payload chunks with matching `deviceShort` are
     *   present (torn write on the writer, or the reader raced a partial upload).
     * - Concatenated payload's SHA-256 does not match the manifest's `fullHash`.
     */
    fun decodeShard(
        ownerDeviceShort: String,
        bookmarks: List<ReadBookmark>,
    ): DecodedShard? {
        val ours = bookmarks.mapNotNull { bm ->
            val parsed = parseTitle(bm.title) ?: return@mapNotNull null
            if (parsed.deviceShort != ownerDeviceShort) return@mapNotNull null
            val slot = parseTimeSlot(bm.time) ?: return@mapNotNull null
            if (slot.chunkIdx != parsed.chunkIdx) return@mapNotNull null // sanity: time and title agree
            parsed
        }
        val manifestPart = ours.firstOrNull { it.chunkIdx == MANIFEST_CHUNK_IDX } ?: return null
        val manifest = try {
            ManifestPayload.fromJson(gunzip(Base64.getDecoder().decode(manifestPart.payloadB64)).decodeToString())
        } catch (_: Exception) {
            return null
        } ?: return null
        val payloadParts = ours.filter { it.chunkIdx in 1..manifest.chunks }.sortedBy { it.chunkIdx }
        if (payloadParts.size != manifest.chunks) return null
        // Reassemble gzipped bytes and validate hash.
        val assembled = ByteArrayOutputStream()
        for (p in payloadParts) {
            assembled.write(Base64.getDecoder().decode(p.payloadB64))
        }
        val bytes = assembled.toByteArray()
        if (sha256(bytes).toHex() != manifest.fullHash) return null
        val json = try {
            gunzip(bytes).decodeToString()
        } catch (_: Exception) {
            return null
        }
        return DecodedShard(
            deviceShort = ownerDeviceShort,
            payload = json,
            manifest = manifest,
        )
    }

    // --- internals -----------------------------------------------------------------------------

    private fun wireChunkOf(
        deviceIdxV: Int,
        deviceShort: String,
        chunkIdx: Int,
        rawContent: ByteArray,
        gzipContent: Boolean,
    ): WireChunk {
        val stored = if (gzipContent) gzip(rawContent) else rawContent
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(stored)
        val hash = sha256(stored).toHex().take(HASH_PREFIX_LEN)
        val title = formatTitle(ParsedTitle(deviceShort, chunkIdx, hash, b64))
        require(title.length <= MAX_TITLE_BYTES) {
            "encoded title ${title.length} exceeds $MAX_TITLE_BYTES; chunkIdx=$chunkIdx"
        }
        return WireChunk(time = timeSlot(deviceIdxV, chunkIdx), title = title)
    }

    /**
     * How many bytes of gzipped payload fit in a chunk title once the header (prefix + colons +
     * deviceShort + chunkIdx + hashPrefix) is accounted for, minus a safety margin. Answered per
     * [deviceShort] (fixed length 8) but conservatively computed against the largest possible
     * chunkIdx (`CHUNKS_PER_DEVICE - 1` — 4 chars).
     */
    private fun payloadCapBytes(deviceShort: String): Int {
        val headerLen = "riffle:v$VERSION::::"
            .length +
            deviceShort.length +
            (CHUNKS_PER_DEVICE - 1).toString().length +
            HASH_PREFIX_LEN
        val remaining = MAX_TITLE_BYTES - headerLen - 32 // 32-byte safety margin
        // base64 without padding: 3 raw bytes → 4 encoded bytes.
        return (remaining / 4) * 3
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // --- data types -----------------------------------------------------------------------------

    /** Coordinates of a reserved bookmark slot. */
    data class TimeSlot(val deviceIdx: Int, val chunkIdx: Int)

    /** Parsed bookmark title. Round-trip safe via [formatTitle]. */
    data class ParsedTitle(
        val deviceShort: String,
        val chunkIdx: Int,
        val contentHashPrefix: String,
        val payloadB64: String,
    )

    /** A ready-to-upload chunk. Caller POSTs/PATCHes at (itemId, [time]) with [title]. */
    data class WireChunk(val time: Int, val title: String)

    /** Minimal read-side shape from `AbsBookmarkApi.listBookmarks`. */
    data class ReadBookmark(val time: Int, val title: String)

    /** Successfully reassembled shard for one device. */
    data class DecodedShard(
        val deviceShort: String,
        val payload: String,
        val manifest: ManifestPayload,
    ) {
        /** Full deviceId, recovered from the manifest. */
        val deviceId: String get() = manifest.deviceId
    }

    /**
     * Manifest content stored in chunk 0. Version + chunk count + full-payload hash + deviceId.
     *
     * Intentionally carries **no wall-clock timestamps** — the manifest must be
     * byte-deterministic for a given `(deviceId, payload)` so that idempotent writes stay
     * idempotent. Per-annotation `createdAt`/`updatedAt` inside the payload remain the source of
     * truth for conflict resolution.
     */
    data class ManifestPayload(
        val version: Int,
        val chunks: Int,
        val encoding: String,
        val fullHash: String,
        val deviceId: String,
    ) {
        fun toJson(): String = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("v", version)
                put("chunks", chunks)
                put("encoding", encoding)
                put("fullHash", fullHash)
                put("deviceId", deviceId)
            },
        )

        companion object {
            fun fromJson(s: String): ManifestPayload? {
                val obj = try {
                    json.parseToJsonElement(s).jsonObject
                } catch (_: Exception) {
                    return null
                }
                val v = obj["v"]?.jsonPrimitive?.intOrNull ?: return null
                val chunks = obj["chunks"]?.jsonPrimitive?.intOrNull ?: return null
                val encoding = obj["encoding"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
                val fullHash = obj["fullHash"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
                val deviceId = obj["deviceId"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
                return ManifestPayload(v, chunks, encoding, fullHash, deviceId)
            }

            private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
                if (isString) content else null
        }
    }
}
