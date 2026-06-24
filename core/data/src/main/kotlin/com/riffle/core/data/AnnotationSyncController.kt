package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
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
 * Gracefully degrades to a no-op if [targetProvider] returns null (sync disabled).
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
        val target = targetProvider() ?: return

        try {
            val filenames = target.list(serverId, itemId)

            // Each device file is a JSON array of W3C annotations (one per annotation the device
            // created), so flat-map the parsed lists.
            val parsedAnnotations = mutableListOf<com.riffle.core.domain.W3CAnnotation>()
            for (filename in filenames) {
                try {
                    val jsonString = target.read(serverId, itemId, filename) ?: continue
                    parsedAnnotations += AnnotationW3CCodec.w3cFileToAnnotations(jsonString)
                } catch (_: Exception) {
                    // Skip corrupt files silently
                }
            }

            val merged = mergeService.merge(parsedAnnotations)

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

            for (entity in entities) {
                annotationDao.upsert(entity)
            }
        } catch (_: Exception) {
            // Graceful error handling: continue silently on any error
        }
    }

    /**
     * Schedule a debounced push of pending annotations.
     *
     * Called after any annotation mutation. Per-book debounce timer; restarts on each call.
     * Cancels any existing pending push for the same book and schedules a new one.
     */
    fun scheduleDebounce(serverId: String, itemId: String) {
        if (targetProvider() == null) return

        val key = serverId to itemId
        debouncingJobs[key]?.cancel()
        debouncingJobs[key] = scope.launch {
            delay(DEBOUNCE_DURATION_MS)
            pushPending(serverId, itemId)
        }
    }

    /**
     * Sync on book close.
     *
     * Cancels any pending debounce timer and pushes pending annotations to the sync target.
     */
    suspend fun syncOnClose(serverId: String, itemId: String) {
        if (targetProvider() == null) return

        val key = serverId to itemId
        debouncingJobs[key]?.cancel()
        debouncingJobs.remove(key)
        pushPending(serverId, itemId)
    }

    /**
     * Write this device's annotations to the sync target.
     *
     * Reads all local non-deleted annotations for a book, serializes them to W3C format,
     * and writes them to a device-specific file.
     */
    private suspend fun pushPending(serverId: String, itemId: String) {
        val target = targetProvider() ?: return

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
            target.write(serverId, itemId, filename, jsonArray)
        } catch (_: Exception) {
            // Graceful error handling: continue silently on any error
        }
    }
}
