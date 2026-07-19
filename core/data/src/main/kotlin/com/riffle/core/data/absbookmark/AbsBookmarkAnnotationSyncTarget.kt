package com.riffle.core.data.absbookmark

import com.riffle.core.data.AnnotationFilenames
import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.NetworkAbsBookmark
import com.riffle.core.network.NetworkResult

/**
 * ABS-bookmark-backed [AnnotationSyncTarget].
 *
 * Each device stores its per-book annotation payload as a set of ABS bookmarks in a Riffle-reserved
 * negative-`time` range. See `AbsBookmarkChunkCodec` and
 * `docs/superpowers/specs/2026-07-19-abs-bookmarks-annotation-sync-design.md`.
 *
 * **Coupling to one ABS account.** A target instance is 1:1 with an ABS `(baseUrl, absUserId)`.
 * `namespace` on every port call is validated against [accountNamespace]; foreign namespaces
 * throw. This mirrors the WebDAV target's single-configuration model.
 *
 * **v1 write policy — full rewrite.** Every `write()` produces the full chunk set for that
 * device+book and rewrites all chunks via POST/PATCH. Trailing bookmarks (from a previous larger
 * payload) are GC'd. A follow-up can add diffing against a local `AbsBookmarkShardStateEntity`
 * cache to skip untouched chunks.
 *
 * **Device meta is a no-op.** ABS bookmarks are per-libraryItem; there is no namespace-scoped
 * key-value slot on ABS's API. Local peer labels come from `AndroidDeviceLabelResolver` on each
 * device; peer labels are not surfaced cross-device via this target. Documented as a v1 gap in
 * the design; sufficient because manifests still carry the full `deviceId` so peers are
 * identifiable in the maintenance UI (just unlabeled).
 */
class AbsBookmarkAnnotationSyncTarget(
    private val baseUrl: String,
    private val token: String,
    private val insecureAllowed: Boolean,
    private val accountNamespace: String,
    private val api: AbsBookmarkApi,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AnnotationSyncTarget {

    override suspend fun list(namespace: String, itemId: String): List<String> {
        requireOwnNamespace(namespace)
        // Group by deviceShort and decode each manifest so we can return the full deviceId in the
        // filename. Peers appear with their real UUID; own device sees itself with the same UUID
        // it wrote — round-trip with `read()` is well-defined regardless of what the caller passes
        // (full deviceId or short — see `read` for the fallback).
        val ours = listOwnBookmarks(itemId)
        val byShort = ours
            .mapNotNull { bm ->
                val parsed = AbsBookmarkChunkCodec.parseTitle(bm.title) ?: return@mapNotNull null
                parsed.deviceShort to bm
            }
            .groupBy({ it.first }, { it.second })
        return byShort.map { (short, bookmarks) ->
            val reads = bookmarks.map { AbsBookmarkChunkCodec.ReadBookmark(it.timeSec, it.title) }
            val fullDeviceId = AbsBookmarkChunkCodec.decodeShard(short, reads)?.deviceId ?: short
            AnnotationFilenames.forDevice(fullDeviceId)
        }
    }

    override suspend fun read(namespace: String, itemId: String, filename: String): String? {
        requireOwnNamespace(namespace)
        val rawFromFilename = AnnotationFilenames.deviceIdOf(filename) ?: return null
        val ownerDeviceShort = deviceShortFor(rawFromFilename)
        val bookmarks = listOwnBookmarks(itemId).map {
            AbsBookmarkChunkCodec.ReadBookmark(it.timeSec, it.title)
        }
        return AbsBookmarkChunkCodec.decodeShard(ownerDeviceShort, bookmarks)?.payload
    }

    override suspend fun write(namespace: String, itemId: String, filename: String, content: String) {
        requireOwnNamespace(namespace)
        val deviceId = AnnotationFilenames.deviceIdOf(filename)
            ?: error("write() filename does not follow annotations-<deviceId>.jsonld: $filename")
        val ownDeviceShort = AbsBookmarkChunkCodec.deviceShort(deviceId)
        val existing = listOwnBookmarks(itemId).mapNotNull { bm ->
            val parsed = AbsBookmarkChunkCodec.parseTitle(bm.title) ?: return@mapNotNull null
            if (parsed.deviceShort != ownDeviceShort) return@mapNotNull null
            bm.timeSec to parsed
        }
        val existingByTime: Map<Int, AbsBookmarkChunkCodec.ParsedTitle> = existing.toMap()

        val wire = AbsBookmarkChunkCodec.encode(deviceId, content, clock())

        // For each chunk: PATCH if slot occupied by us with different content, POST if empty, skip if identical.
        for (chunk in wire) {
            val prior = existingByTime[chunk.time]
            when {
                prior == null -> {
                    api.createBookmark(baseUrl, itemId, chunk.time, chunk.title, token, insecureAllowed)
                        .requireOkOrThrow("createBookmark(item=$itemId, time=${chunk.time})")
                }
                titlesEqual(prior, chunk.title) -> {
                    // Unchanged — skip.
                }
                else -> {
                    api.updateBookmark(baseUrl, itemId, chunk.time, chunk.title, token, insecureAllowed)
                        .requireOkOrThrow("updateBookmark(item=$itemId, time=${chunk.time})")
                }
            }
        }

        // GC our own trailing chunks that the new payload no longer needs.
        val newTimes = wire.map { it.time }.toSet()
        for ((oldTime, _) in existing) {
            if (oldTime !in newTimes) {
                api.deleteBookmark(baseUrl, itemId, oldTime, token, insecureAllowed)
                    .requireOkOrThrow("deleteBookmark(item=$itemId, time=$oldTime)")
            }
        }
    }

    override suspend fun delete(namespace: String, itemId: String, filename: String) {
        requireOwnNamespace(namespace)
        val rawFromFilename = AnnotationFilenames.deviceIdOf(filename) ?: return
        val ownerDeviceShort = deviceShortFor(rawFromFilename)
        val bookmarks = listOwnBookmarks(itemId)
        for (bm in bookmarks) {
            val parsed = AbsBookmarkChunkCodec.parseTitle(bm.title) ?: continue
            if (parsed.deviceShort != ownerDeviceShort) continue
            api.deleteBookmark(baseUrl, itemId, bm.timeSec, token, insecureAllowed)
                .requireOkOrThrow("deleteBookmark(item=$itemId, time=${bm.timeSec})")
        }
    }

    override suspend fun readDeviceMeta(namespace: String, deviceId: String): String? {
        // v1: no cross-device meta surface on ABS; see class kdoc.
        requireOwnNamespace(namespace)
        return null
    }

    override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {
        requireOwnNamespace(namespace)
        // No-op. See class kdoc.
    }

    override suspend fun deleteDeviceMeta(namespace: String, deviceId: String) {
        requireOwnNamespace(namespace)
        // No-op. See class kdoc.
    }

    override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing {
        requireOwnNamespace(namespace)
        val all = listAllBookmarksInReservedRange()
        // Group by (deviceShort, itemId). For each device we recover the full deviceId from at
        // least one manifest so the maintenance UI can label the row.
        data class ShardBookmarks(val bookmarks: MutableList<NetworkAbsBookmark> = mutableListOf())
        val byDevice = HashMap<String, HashMap<String, ShardBookmarks>>()
        for (bm in all) {
            val parsed = AbsBookmarkChunkCodec.parseTitle(bm.title) ?: continue
            val perItem = byDevice.getOrPut(parsed.deviceShort) { HashMap() }
            perItem.getOrPut(bm.libraryItemId) { ShardBookmarks() }.bookmarks.add(bm)
        }
        val devices = byDevice.map { (deviceShort, perItemMap) ->
            // Recover full deviceId from any decodable manifest for this device.
            val recoveredId = perItemMap.values.firstNotNullOfOrNull { sb ->
                val reads = sb.bookmarks.map { AbsBookmarkChunkCodec.ReadBookmark(it.timeSec, it.title) }
                AbsBookmarkChunkCodec.decodeShard(deviceShort, reads)?.deviceId
            } ?: deviceShort
            DeviceFileSummary(
                deviceId = recoveredId,
                annotationFiles = perItemMap.keys.map { itemId ->
                    AnnotationFileRef(itemId = itemId, filename = AnnotationFilenames.forDevice(deviceShort))
                },
            )
        }
        return NamespaceDeviceListing(devices = devices)
    }

    override suspend fun enumerateNamespaces(): List<NamespaceSummary> {
        // ABS bookmarks are scoped to a single ABS user (this target's token). There's only ever
        // one namespace visible from an ABS-bookmark target — this one.
        val count = listAllBookmarksInReservedRange().count { AbsBookmarkChunkCodec.parseTitle(it.title) != null }
        if (count == 0) return emptyList()
        return listOf(NamespaceSummary(namespace = accountNamespace, annotationFileCount = count))
    }

    override suspend fun forgetNamespace(namespace: String): Int {
        requireOwnNamespace(namespace)
        val all = listAllBookmarksInReservedRange()
        var deleted = 0
        for (bm in all) {
            AbsBookmarkChunkCodec.parseTitle(bm.title) ?: continue
            // Sanity: only touch our reserved range.
            AbsBookmarkChunkCodec.parseTimeSlot(bm.timeSec) ?: continue
            val result = api.deleteBookmark(baseUrl, bm.libraryItemId, bm.timeSec, token, insecureAllowed)
            if (result is NetworkResult.Success) {
                deleted++
            }
            // A per-file delete failure here is transient (network); we don't roll back partial
            // successes — the sweep worker retries on next flush.
        }
        return deleted
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * Map a filename's device segment to the 8-char deviceShort used inside titles. Filenames
     * returned by [list]/[enumerateDevices] embed the full deviceId; but a torn shard (no manifest)
     * falls back to the deviceShort. Callers can therefore pass either. Detect by hex-8 shape.
     */
    private fun deviceShortFor(rawFromFilename: String): String =
        if (rawFromFilename.length == 8 && rawFromFilename.all { it in '0'..'9' || it in 'a'..'f' }) {
            rawFromFilename
        } else {
            AbsBookmarkChunkCodec.deviceShort(rawFromFilename)
        }

    private fun requireOwnNamespace(namespace: String) {
        require(namespace == accountNamespace) {
            "AbsBookmarkAnnotationSyncTarget bound to namespace=$accountNamespace, got $namespace"
        }
    }

    /** Every bookmark this ABS account currently holds; caller filters. */
    private suspend fun listAllBookmarks(): List<NetworkAbsBookmark> {
        val result = api.listBookmarks(baseUrl, token, insecureAllowed)
        return when (result) {
            is NetworkResult.Success -> result.value
            else -> throw AbsBookmarkTargetException("listBookmarks failed: $result")
        }
    }

    /** Bookmarks for [itemId] in Riffle's reserved negative-time range. */
    private suspend fun listOwnBookmarks(itemId: String): List<NetworkAbsBookmark> =
        listAllBookmarks().filter {
            it.libraryItemId == itemId && AbsBookmarkChunkCodec.parseTimeSlot(it.timeSec) != null
        }

    /** All bookmarks in Riffle's reserved range, across every book. */
    private suspend fun listAllBookmarksInReservedRange(): List<NetworkAbsBookmark> =
        listAllBookmarks().filter { AbsBookmarkChunkCodec.parseTimeSlot(it.timeSec) != null }

    private fun titlesEqual(existing: AbsBookmarkChunkCodec.ParsedTitle, newTitle: String): Boolean {
        val parsedNew = AbsBookmarkChunkCodec.parseTitle(newTitle) ?: return false
        // Match by the content-hash prefix — the hash is over the raw stored bytes and changes
        // whenever the payload changes.
        return existing.contentHashPrefix == parsedNew.contentHashPrefix &&
            existing.payloadB64 == parsedNew.payloadB64
    }
}

class AbsBookmarkTargetException(message: String) : RuntimeException(message)

private fun <T> NetworkResult<T>.requireOkOrThrow(op: String): T = when (this) {
    is NetworkResult.Success -> value
    else -> throw AbsBookmarkTargetException("$op → $this")
}
