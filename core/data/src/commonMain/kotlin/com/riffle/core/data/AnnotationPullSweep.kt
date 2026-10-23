package com.riffle.core.data

import com.riffle.core.common.platformSystemClock
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.sync.AnnotationSyncStatusStore
import com.riffle.core.sync.CycleOutcome
import kotlinx.coroutines.flow.first

/**
 * Pull-only sweep that fetches remote annotation files for all configured sources into Room.
 * Complement to the push-only [AnnotationSweep]: where the push sweep flushes locally-dirty
 * rows out to WebDAV, this sweep pulls peer files in — populating the elided view without
 * requiring each book to be individually opened in the reader.
 *
 * Discovers books with remote peer files via [AnnotationSyncTarget.enumerateDevices] (a single
 * PROPFIND per namespace) and merges them using the same LWW policy as the per-book
 * [AnnotationSyncController.syncOnOpen].
 *
 * Unconfigured target ⇒ silent no-op; status store is not touched.
 */
class AnnotationPullSweep(
    private val targetProvider: suspend () -> AnnotationSyncTarget?,
    private val syncController: AnnotationSyncController,
    private val sourceRepository: SourceRepository,
    private val statusStore: AnnotationSyncStatusStore,
    private val clock: () -> Long = platformSystemClock::nowMs,
) {
    suspend fun run(): CycleOutcome? {
        targetProvider() ?: return null

        val outcome: CycleOutcome = try {
            val sources = sourceRepository.observeAll().first()
            for (source in sources) {
                val namespace = (
                    sourceRepository.ensureSyncNamespace(source.id)
                        as? SyncNamespace.Configured
                )?.value ?: continue
                syncController.pullAll(source.id, namespace)
            }
            CycleOutcome.Success(clock())
        } catch (e: Exception) {
            e.toFailedCycleOutcome(clock())
        }
        statusStore.report(outcome)
        return outcome
    }
}
