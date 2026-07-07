package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.AnnotationFileHeader
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Facade for annotation sync lifecycle. Composes three collaborators behind the
 * [AnnotationSyncTarget] seam:
 *
 * - [AnnotationMergeOrchestrator] — open-book read → merge → upsert
 * - [AnnotationLiveSync] — per-book pull loop (delegates to the orchestrator when a peer file is present)
 * - [AnnotationPushCoordinator] — debounced push + close-flush behind the [AnnotationLockPort]
 *
 * The two identity axes are unchanged:
 * - `sourceId` — per-device local Riffle id, used to scope the Room DAO query.
 * - `namespace` — cross-device-stable ABS account id, used for the sync target's path/key so two
 *   devices configured against the same ABS server discover each other's files. See
 *   [AnnotationSyncTarget] kdoc for the rationale.
 *
 * Gracefully degrades to a no-op if [targetProvider] returns null (sync disabled). Companion sweep
 * ([AnnotationSweep], ADR 0036) handles durable retries after process death; this controller
 * reports its own cycle outcomes through [statusStore] and asks [sweepEnqueuer] to schedule a
 * WorkManager retry on failure.
 */
class AnnotationSyncController(
    private val targetProvider: () -> AnnotationSyncTarget?,
    mergeService: AnnotationMergeService,
    annotationDao: AnnotationDao,
    deviceIdStore: DeviceIdStore,
    deviceLabelResolver: DeviceLabelResolver,
    scope: CoroutineScope,
    statusStore: AnnotationSyncStatusStore,
    sweepEnqueuer: AnnotationSweepEnqueuer,
    /**
     * Resolves the login username for a local Riffle server id. Used to embed the human-
     * readable account name in each file's [AnnotationFileHeader] so the Maintenance screen
     * can label foreign-user groups by name instead of by opaque user id. Returns null when the
     * server is unknown or doesn't carry credentials (Storyteller peer, etc.).
     */
    usernameProvider: suspend (sourceId: String) -> String? = { null },
    /**
     * Resolves the local catalog's book title for a (sourceId, itemId). Embedded in the header
     * so Maintenance can surface "Project Hail Mary" instead of an opaque itemId. Returns null
     * when the catalog hasn't cached the title yet — header renderer falls back to the id.
     */
    bookTitleProvider: suspend (sourceId: String, itemId: String) -> String? = { _, _ -> null },
    nowIso: () -> String = { Instant.now().toString() },
    clock: () -> Long = System::currentTimeMillis,
    /**
     * Per-book mutex shared with [AnnotationSweep] (#321). Held across read-then-write so the
     * sweep and a live push cannot interleave on the same device file. Defaults to a fresh
     * instance for tests; production wiring (DI) supplies the process-wide singleton via
     * [ReconcileLocks].
     */
    locks: AnnotationLockPort = ReconcileLocks(),
    /**
     * Shared sentinel-writer (#321). Defaults to one built from this controller's own deps so
     * existing tests don't have to construct one; production wiring injects the singleton.
     */
    sentinelWriter: DeviceMetaSentinelWriter = DeviceMetaSentinelWriter(
        deviceIdStore,
        deviceLabelResolver,
        usernameProvider,
        nowIso,
    ),
) {
    companion object {
        private const val DEBOUNCE_DURATION_MS = 1000L
        private const val LIVE_SYNC_INTERVAL_MS = 30_000L

        /**
         * ADR 0038 — tombstones and the ignore-stale-orphan merge guard both use this cutoff.
         * 90 days: cheap lever to reduce the "device offline > TTL, then edits a ghost" resurrection
         * risk without meaningfully changing tidiness benefit. Not a user setting.
         */
        internal const val TOMBSTONE_TTL_MS = 90L * 24L * 60L * 60L * 1000L
    }

    private val mergeOrchestrator = AnnotationMergeOrchestrator(
        mergeService = mergeService,
        annotationDao = annotationDao,
        deviceIdStore = deviceIdStore,
        statusStore = statusStore,
        sentinelWriter = sentinelWriter,
        clock = clock,
        tombstoneTtlMs = TOMBSTONE_TTL_MS,
    )

    private val liveSync = AnnotationLiveSync(
        targetProvider = targetProvider,
        orchestrator = mergeOrchestrator,
        deviceIdStore = deviceIdStore,
        statusStore = statusStore,
        sentinelWriter = sentinelWriter,
        scope = scope,
        clock = clock,
        liveSyncIntervalMs = LIVE_SYNC_INTERVAL_MS,
    )

    private val pushCoordinator = AnnotationPushCoordinator(
        targetProvider = targetProvider,
        annotationDao = annotationDao,
        deviceIdStore = deviceIdStore,
        statusStore = statusStore,
        sweepEnqueuer = sweepEnqueuer,
        sentinelWriter = sentinelWriter,
        locks = locks,
        bookTitleProvider = bookTitleProvider,
        scope = scope,
        clock = clock,
        tombstoneTtlMs = TOMBSTONE_TTL_MS,
        debounceDurationMs = DEBOUNCE_DURATION_MS,
    )

    /**
     * Full sync on book open.
     *
     * @param sourceId Local per-device Riffle server id (DAO scope).
     * @param namespace Cross-device-stable account id (sync-target scope).
     * @param itemId The ABS library item ID.
     */
    suspend fun syncOnOpen(sourceId: String, namespace: String, itemId: String) {
        val target = targetProvider() ?: return
        mergeOrchestrator.syncOnOpen(target, sourceId, namespace, itemId)
    }

    /**
     * Start a per-book pull loop. Returns the [Job] backing the loop; callers must cancel it when
     * the reader stops or the book closes. Backgrounding/foregrounding is handled by the caller
     * via cancel + restart.
     */
    fun startLiveSync(sourceId: String, namespace: String, itemId: String): Job =
        liveSync.start(sourceId, namespace, itemId)

    /**
     * Schedule a debounced push of pending annotations. Per-book timer; restarts on each call.
     */
    fun scheduleDebounce(sourceId: String, namespace: String, itemId: String) {
        pushCoordinator.scheduleDebounce(sourceId, namespace, itemId)
    }

    /**
     * Sync on book close. Cancels any pending debounce timer and pushes pending annotations.
     */
    suspend fun syncOnClose(sourceId: String, namespace: String, itemId: String) {
        pushCoordinator.syncOnClose(sourceId, namespace, itemId)
    }
}
