package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.W3CAnnotation

/**
 * Owns the read-list → merge → upsert path executed on book-open and reused by the live-sync
 * poll loop when peer files exist. Extracted from [AnnotationSyncController] so the merge policy
 * (ADR 0038 tombstone sweep + stale-orphan guard) can be exercised in isolation.
 */
internal class AnnotationMergeOrchestrator(
    private val mergeService: AnnotationMergeService,
    private val annotationDao: AnnotationDao,
    private val deviceIdStore: DeviceIdStore,
    private val statusStore: AnnotationSyncStatusStore,
    private val sentinelWriter: DeviceMetaSentinelWriter,
    private val clock: () -> Long,
    private val tombstoneTtlMs: Long,
) {
    /** Full sync on book open: list peer files, read each, merge, upsert. */
    suspend fun syncOnOpen(
        target: AnnotationSyncTarget,
        serverId: String,
        namespace: String,
        itemId: String,
    ) {
        val filenames = try {
            target.list(namespace, itemId)
        } catch (e: Exception) {
            statusStore.report(e.toFailedCycleOutcome(clock()))
            // Don't enqueue a sweep — sync-on-open failures are retried the next book open.
            return
        }
        mergeFromListing(target, serverId, namespace, itemId, filenames)
    }

    /**
     * Merge the device files already listed in [filenames] into Room using the same [target]
     * instance that produced the listing. Reports a [CycleOutcome] to the status store on
     * success or failure.
     */
    suspend fun mergeFromListing(
        target: AnnotationSyncTarget,
        serverId: String,
        namespace: String,
        itemId: String,
        filenames: List<String>,
    ) {
        try {
            val parsedAnnotations = mutableListOf<W3CAnnotation>()
            for (filename in filenames) {
                try {
                    val jsonString = target.read(namespace, itemId, filename) ?: continue
                    parsedAnnotations += AnnotationW3CCodec.w3cFileToAnnotations(jsonString)
                } catch (_: Exception) {
                    // Skip corrupt files silently
                }
            }

            // ADR 0038 rule 1+2 — sweep aged tombstones and DELETE the resulting empty file.
            // Ordering matters: compute the hypothetical post-sweep state in memory FIRST, so we
            // can attempt the DELETE before mutating Room. If the DELETE throws, Room is untouched
            // and the next sync sees the same transition and retries. Sweeping first would leave
            // Room empty with a stale file on WebDAV and no signal to retry the DELETE.
            val now = clock()
            val cutoff = now - tombstoneTtlMs
            val beforeSweep = annotationDao.getAllForItemIncludingDeleted(serverId, itemId)
            val nonPurgeable = beforeSweep.filter { !it.isAgedSyncedTomb(cutoff) }
            val localById = nonPurgeable.associateBy { it.id }
            val localExisting = nonPurgeable.map { AnnotationW3CCodec.entityToW3CAnnotation(it) }

            // ADR 0038 rule 3 — ignore stale orphans. Incoming rows we've never seen locally and
            // whose updatedAt is past TTL are the delayed push from a peer that missed a
            // household-wide sweep; applying them would silently resurrect long-deleted content.
            val merged = mergeService.merge(
                parsed = parsedAnnotations,
                existing = localExisting,
                nowMs = now,
                staleOrphanCutoffMs = tombstoneTtlMs,
            )

            // Rule 2 DELETE branch — the sweep would empty us and this device's own file is on
            // WebDAV. DELETE first, then commit the sweep and any merge results.
            if (merged.isEmpty() && beforeSweep.isNotEmpty()) {
                val ownFilename = AnnotationFilenames.forDevice(deviceIdStore.getOrCreate())
                if (ownFilename in filenames) {
                    target.delete(namespace, itemId, ownFilename)
                }
            }

            // Safe to commit Room mutations now — either no DELETE was needed, or it succeeded.
            annotationDao.purgeAgedTombstones(serverId, itemId, cutoff)

            val entities = merged.map { w3cAnnotation ->
                val existing = localById[w3cAnnotation.id]
                val preservedLastSyncedAt = when {
                    existing == null -> 0L
                    existing.updatedAt >= w3cAnnotation.updatedAt -> existing.lastSyncedAt
                    else -> 0L
                }
                AnnotationEntity(
                    id = w3cAnnotation.id,
                    serverId = serverId,
                    itemId = itemId,
                    type = w3cAnnotation.type,
                    cfi = w3cAnnotation.cfi,
                    color = w3cAnnotation.color ?: AnnotationEntity.COLOR_YELLOW,
                    note = w3cAnnotation.note,
                    textSnippet = w3cAnnotation.textSnippet,
                    textBefore = w3cAnnotation.textBefore,
                    textAfter = w3cAnnotation.textAfter,
                    chapterHref = w3cAnnotation.chapterHref,
                    spineIndex = 0,
                    progression = 0.0,
                    bookmarkTitle = w3cAnnotation.bookmarkTitle ?: "",
                    createdAt = w3cAnnotation.createdAt,
                    updatedAt = w3cAnnotation.updatedAt,
                    originDeviceId = w3cAnnotation.originDeviceId,
                    lastModifiedByDeviceId = w3cAnnotation.lastModifiedByDeviceId,
                    deleted = w3cAnnotation.deleted,
                    lastSyncedAt = preservedLastSyncedAt,
                )
            }

            for (entity in entities) {
                annotationDao.upsert(entity)
            }
            statusStore.report(CycleOutcome.Success(clock()))
            sentinelWriter.writeQuietly(target, namespace, serverId)
        } catch (e: Exception) {
            statusStore.report(e.toFailedCycleOutcome(clock()))
        }
    }

}
