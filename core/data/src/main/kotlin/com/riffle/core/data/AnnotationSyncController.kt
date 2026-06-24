package com.riffle.core.data

import android.util.Log
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RIFFLE_ANNO_SYNC"

/**
 * Orchestrator for annotation sync lifecycle.
 *
 * Manages the three sync events:
 * - [syncOnOpen]: Full sync when a book is opened (read all device files, merge, upsert).
 * - [scheduleDebounce]: Per-book debounce timer on any annotation mutation.
 * - [syncOnClose]: Cancel debounce and push pending changes when a book is closed.
 *
 * Gracefully degrades to a no-op if [target] is null (sync disabled).
 */
class AnnotationSyncController(
    private val targetProvider: () -> AnnotationSyncTarget?,
    private val mergeService: AnnotationMergeService,
    private val annotationDao: AnnotationDao,
    private val deviceIdStore: DeviceIdStore,
    private val scope: CoroutineScope,
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
     * @param serverId The ABS server ID.
     * @param itemId The ABS library item ID.
     */
    suspend fun syncOnOpen(serverId: String, itemId: String) {
        val target = targetProvider()
        if (target == null) {
            Log.d(TAG, "syncOnOpen($serverId/$itemId) skipped — no sync target configured")
            return
        }

        try {
            Log.d(TAG, "syncOnOpen($serverId/$itemId) — listing device files")
            // Step 1: List all annotation files
            val filenames = target.list(serverId, itemId)
            Log.d(TAG, "syncOnOpen — found ${filenames.size} device file(s): $filenames")

            // Step 2: Read and parse each file
            val parsedAnnotations = mutableListOf<com.riffle.core.domain.W3CAnnotation>()
            for (filename in filenames) {
                try {
                    val jsonString = target.read(serverId, itemId, filename) ?: continue
                    val parsed = AnnotationW3CCodec.w3cToAnnotationEntity(jsonString)
                    // Skip corrupt files (empty id is a sign of parsing failure)
                    if (parsed.id.isNotEmpty()) {
                        parsedAnnotations.add(parsed)
                    }
                } catch (_: Exception) {
                    // Skip corrupt files silently
                }
            }

            // Step 3: Merge parsed annotations
            val merged = mergeService.merge(parsedAnnotations)

            // Step 4: Convert to AnnotationEntity and upsert
            val entities = merged.map { w3cAnnotation ->
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
                )
            }

            // Upsert all merged entities
            for (entity in entities) {
                annotationDao.upsert(entity)
            }
            Log.d(TAG, "syncOnOpen — merged ${entities.size} annotation(s) into Room")
        } catch (e: Exception) {
            Log.w(TAG, "syncOnOpen($serverId/$itemId) failed", e)
        }
    }

    /**
     * Schedule a debounced push of pending annotations.
     *
     * Called after any annotation mutation. Per-book debounce timer; restarts on each call.
     * Cancels any existing pending push for the same book and schedules a new one.
     *
     * @param serverId The ABS server ID.
     * @param itemId The ABS library item ID.
     */
    fun scheduleDebounce(serverId: String, itemId: String) {
        if (targetProvider() == null) {
            Log.d(TAG, "scheduleDebounce($serverId/$itemId) skipped — no sync target configured")
            return
        }

        val key = serverId to itemId

        // Cancel existing debounce job for this book
        debouncingJobs[key]?.cancel()

        Log.d(TAG, "scheduleDebounce($serverId/$itemId) — push in ${DEBOUNCE_DURATION_MS}ms")
        // Schedule a new debounce job
        debouncingJobs[key] = scope.launch {
            delay(DEBOUNCE_DURATION_MS)
            pushPending(serverId, itemId)
        }
    }

    /**
     * Sync on book close.
     *
     * Cancels any pending debounce timer and pushes pending annotations to the sync target.
     * Called on DisposableEffect.onDispose().
     *
     * @param serverId The ABS server ID.
     * @param itemId The ABS library item ID.
     */
    suspend fun syncOnClose(serverId: String, itemId: String) {
        if (targetProvider() == null) return

        val key = serverId to itemId

        // Cancel any pending debounce
        debouncingJobs[key]?.cancel()
        debouncingJobs.remove(key)

        // Push pending changes immediately
        pushPending(serverId, itemId)
    }

    /**
     * Write this device's annotations to the sync target.
     *
     * Reads all local non-deleted annotations for a book, serializes them to W3C format,
     * and writes them to a device-specific file.
     *
     * @param serverId The ABS server ID.
     * @param itemId The ABS library item ID.
     */
    private suspend fun pushPending(serverId: String, itemId: String) {
        val target = targetProvider()
        if (target == null) {
            Log.d(TAG, "pushPending($serverId/$itemId) skipped — no sync target configured")
            return
        }

        try {
            val deviceId = deviceIdStore.getOrCreate()
            val localEntities = annotationDao.getForItem(serverId, itemId)
            val jsonStrings = localEntities.map { entity ->
                AnnotationW3CCodec.annotationEntityToW3C(entity)
            }
            val jsonArray = if (jsonStrings.isEmpty()) {
                "[]"
            } else {
                "[\n" + jsonStrings.joinToString(",\n") + "\n]"
            }
            val filename = "annotations-$deviceId.jsonld"
            Log.d(
                TAG,
                "pushPending($serverId/$itemId) — PUT $filename (${localEntities.size} annotation(s), ${jsonArray.length} bytes)",
            )
            target.write(serverId, itemId, filename, jsonArray)
            Log.d(TAG, "pushPending($serverId/$itemId) — write succeeded")
        } catch (e: Exception) {
            Log.w(TAG, "pushPending($serverId/$itemId) failed", e)
        }
    }
}
