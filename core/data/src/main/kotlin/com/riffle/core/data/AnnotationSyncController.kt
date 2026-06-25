package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.DeviceMetadata
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Orchestrator for annotation sync lifecycle.
 *
 * Manages the three sync events:
 * - [syncOnOpen]: Full sync when a book is opened (read all device files, merge, upsert).
 * - [scheduleDebounce]: Per-book debounce timer on any annotation mutation.
 * - [syncOnClose]: Cancel debounce and push pending changes when a book is closed.
 *
 * **Two identities at play.** Every call takes both:
 * - `serverId` — the per-device local Riffle id, used to scope the Room DAO query. The local
 *   DB is always per-device, so this is correct here.
 * - `namespace` — the cross-device-stable ABS account id ([com.riffle.core.domain.Server.absUserId]),
 *   used for the sync target's path/key so two devices configured against the same ABS server
 *   discover each other's files. See [AnnotationSyncTarget] kdoc for the rationale.
 *
 * Callers that don't yet know `namespace` (legacy rows pre-`servers.absUserId`) should resolve
 * it via [com.riffle.core.domain.ServerRepository.ensureAbsUserId]; if that returns null,
 * skip sync entirely — the local DB remains the source of truth.
 *
 * Gracefully degrades to a no-op if [targetProvider] returns null (sync disabled).
 *
 * Companion sweep ([AnnotationSweep], ADR 0036) handles durable retries after process death;
 * this controller reports its own cycle outcomes through [statusStore] and asks [sweepEnqueuer]
 * to schedule a WorkManager retry on failure so the catch-up isn't tied to staying foregrounded.
 */
class AnnotationSyncController(
    private val targetProvider: () -> AnnotationSyncTarget?,
    private val mergeService: AnnotationMergeService,
    private val annotationDao: AnnotationDao,
    private val deviceIdStore: DeviceIdStore,
    private val deviceLabelResolver: DeviceLabelResolver,
    private val scope: CoroutineScope,
    private val statusStore: AnnotationSyncStatusStore,
    private val sweepEnqueuer: AnnotationSweepEnqueuer,
    private val nowIso: () -> String = { Instant.now().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val DEBOUNCE_DURATION_MS = 1000L
    }

    private val debouncingJobs = mutableMapOf<Pair<String, String>, Job>()

    /**
     * Full sync on book open.
     *
     * Reads all device annotation files for a book, parses them, merges with last-write-wins,
     * and upserts the merged result to Room. Called once when EpubReaderScreen composes.
     *
     * @param serverId Local per-device Riffle server id (DAO scope).
     * @param namespace Cross-device-stable account id (sync-target scope). See class kdoc.
     * @param itemId The ABS library item ID.
     */
    suspend fun syncOnOpen(serverId: String, namespace: String, itemId: String) {
        val target = targetProvider() ?: return

        try {
            val filenames = target.list(namespace, itemId)

            // Each device file is a JSON array of W3C annotations (one per annotation the device
            // created), so flat-map the parsed lists.
            val parsedAnnotations = mutableListOf<com.riffle.core.domain.W3CAnnotation>()
            for (filename in filenames) {
                try {
                    val jsonString = target.read(namespace, itemId, filename) ?: continue
                    parsedAnnotations += AnnotationW3CCodec.w3cFileToAnnotations(jsonString)
                } catch (_: Exception) {
                    // Skip corrupt files silently
                }
            }

            // Seed merge with the local set (including tombstones) so LWW spans both sides — a
            // newer local edit isn't clobbered by an older remote copy, and a local tombstone wins
            // over a remote pre-delete entry.
            val localExistingEntities = annotationDao
                .getAllForItemIncludingDeleted(serverId, itemId)
            val localById = localExistingEntities.associateBy { it.id }
            val localExisting = localExistingEntities
                .map { AnnotationW3CCodec.entityToW3CAnnotation(it) }

            val merged = mergeService.merge(parsed = parsedAnnotations, existing = localExisting)

            val entities = merged.map { w3cAnnotation ->
                val existing = localById[w3cAnnotation.id]
                // Preserve the existing lastSyncedAt stamp when this device's row was the LWW winner.
                // If there's no local row (purely remote content) or the remote row won (our row is
                // being overwritten by content this device hasn't pushed), mark as dirty (0) so the
                // next sweep pushes it.
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
        } catch (e: Exception) {
            statusStore.report(e.toFailedCycleOutcome(clock()))
            // Don't enqueue a sweep here — sync-on-open failures are recovered by the next book
            // open, which retries the read pass. There is nothing for the push sweep to do.
        }
    }

    /**
     * Schedule a debounced push of pending annotations.
     *
     * Called after any annotation mutation. Per-book debounce timer; restarts on each call.
     * Cancels any existing pending push for the same book and schedules a new one.
     */
    fun scheduleDebounce(serverId: String, namespace: String, itemId: String) {
        if (targetProvider() == null) return

        val key = serverId to itemId
        debouncingJobs[key]?.cancel()
        debouncingJobs[key] = scope.launch {
            delay(DEBOUNCE_DURATION_MS)
            pushPending(serverId, namespace, itemId)
        }
    }

    /**
     * Sync on book close.
     *
     * Cancels any pending debounce timer and pushes pending annotations to the sync target.
     */
    suspend fun syncOnClose(serverId: String, namespace: String, itemId: String) {
        if (targetProvider() == null) return

        val key = serverId to itemId
        debouncingJobs[key]?.cancel()
        debouncingJobs.remove(key)
        pushPending(serverId, namespace, itemId)
    }

    /**
     * Write this device's annotations to the sync target.
     *
     * Reads all local non-deleted annotations for a book, serializes them to W3C format,
     * and writes them to a device-specific file. On success, stamps lastSyncedAt and reports
     * Success; on failure, reports a typed failure outcome and enqueues a WorkManager retry.
     */
    private suspend fun pushPending(serverId: String, namespace: String, itemId: String) {
        val target = targetProvider() ?: return

        try {
            // Include tombstones so deletes propagate; an annotation with deleted=1 still carries
            // its updatedAt/lastModifiedByDeviceId, and the LWW merge on the receiver will trust
            // the newer tombstone over any older live copy of the same id.
            val localEntities = annotationDao.getAllForItemIncludingDeleted(serverId, itemId)
            // Don't overwrite this device's existing remote file with an empty list when local has
            // nothing — that erases the cloud copy of annotations on transient local-empty states
            // (cleared data, mid-migration). We only push when there's at least one row (live or
            // tombstoned) to record. Genuine "user deleted everything" still propagates because
            // each delete leaves a tombstone in this list.
            if (localEntities.isEmpty()) return

            val deviceId = deviceIdStore.getOrCreate()
            val jsonStrings = localEntities.map { entity ->
                AnnotationW3CCodec.annotationEntityToW3C(entity)
            }
            // Embed device metadata as a header object at position 0 of the array. Old readers
            // already drop entries with no `id`, so the header is invisible to merge.
            val metadata = DeviceMetadata(
                deviceId = deviceId,
                label = deviceLabelResolver.resolveLabel(deviceId),
                lastSeenAt = nowIso(),
            )
            val jsonArray = DeviceMetadataCodec.buildFileBody(metadata, jsonStrings)
            val filename = "annotations-$deviceId.jsonld"
            target.write(namespace, itemId, filename, jsonArray)
            val now = clock()
            annotationDao.markSynced(localEntities.map { it.id }, now)
            statusStore.report(CycleOutcome.Success(now))
        } catch (e: Exception) {
            statusStore.report(e.toFailedCycleOutcome(clock()))
            sweepEnqueuer.enqueue()
        }
    }
}
