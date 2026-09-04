package com.riffle.feature.player

import com.riffle.core.domain.AudiobookPositionReconciler
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.common.Clock

/**
 * Computes the position to start playback at when opening an audiobook.
 *
 * Extracted from [AudiobookPlayerViewModel] (issue #345, slice 2). Concentrates the last-update-
 * wins reconcile (ADR 0035), the offline "no tracked position" progress-fraction fallback, the
 * finished-book normalisation, and the readaloud→audiobook handoff override.
 */
class AudiobookResumeResolver constructor(
    private val positionStore: AudiobookPositionStore,
    private val clock: Clock,
) {
    /**
     * Resolved playback resume.
     *
     * @property resumeSec book-absolute seconds the player should start at.
     * @property resumeStamp the timestamp associated with [resumeSec].
     * @property wasFinishedOnOpen true when the reconciled resume was at/near the end of the book.
     */
    data class ResumePoint(
        val resumeSec: Double,
        val resumeStamp: Long,
        val wasFinishedOnOpen: Boolean = false,
    )

    /**
     * Compute the resume for a fresh player-open.
     *
     * @param startAtSec [AudiobookPlayerViewModel.startAtSec] value: `< 0` = normal open;
     *   `>= 0` = readaloud→audiobook handoff (override with this value).
     */
    suspend fun resolve(
        sourceId: String,
        itemId: String,
        session: AudiobookSession,
        readingProgressFraction: Float,
        startAtSec: Double,
    ): ResumePoint {
        val localSec = if (sourceId.isNotEmpty()) positionStore.load(sourceId, itemId) else null
        val localTs = if (sourceId.isNotEmpty()) positionStore.loadLocalUpdatedAt(sourceId, itemId) else 0L
        val decision = AudiobookPositionReconciler.reconcile(
            localSec = localSec,
            localUpdatedAt = localTs,
            remoteSec = session.serverCurrentTimeSec,
            remoteUpdatedAt = session.serverLastUpdate,
        )
        var resumeSec: Double
        val reconciledStamp: Long
        when (decision) {
            is AudiobookPositionReconciler.Decision.PullRemote -> {
                if (sourceId.isNotEmpty()) {
                    positionStore.save(sourceId, itemId, decision.positionSec)
                    positionStore.updateLocalTimestamp(sourceId, itemId, decision.timestampMillis)
                }
                resumeSec = decision.positionSec
                reconciledStamp = decision.timestampMillis
            }
            is AudiobookPositionReconciler.Decision.PushLocal -> {
                resumeSec = decision.positionSec
                reconciledStamp = decision.timestampMillis
            }
            AudiobookPositionReconciler.Decision.InSync -> {
                resumeSec = session.serverCurrentTimeSec
                reconciledStamp = session.serverLastUpdate
            }
        }

        resumeSec = audiobookResumeSec(
            reconciledSec = resumeSec,
            hadTrackedPosition = reconciledStamp > 0L,
            readingProgressFraction = readingProgressFraction,
            durationSec = session.timeline.durationSec,
        )

        val resumeBeforeFinishedGuard = resumeSec
        if (startAtSec < 0.0) {
            resumeSec = audiobookStartSec(resumeSec, session.timeline.durationSec)
        }
        val durationSec = session.timeline.durationSec
        val wasFinishedOnOpen = startAtSec < 0.0
            && durationSec > 0.0
            && resumeBeforeFinishedGuard >= durationSec - AUDIOBOOK_FINISHED_EPS_SEC

        var resumeStamp = reconciledStamp
        if (startAtSec >= 0.0) {
            resumeSec = startAtSec.coerceIn(0.0, session.timeline.durationSec)
            resumeStamp = clock.nowMs()
            if (sourceId.isNotEmpty()) {
                positionStore.save(sourceId, itemId, resumeSec)
                positionStore.updateLocalTimestamp(sourceId, itemId, resumeStamp)
            }
        }

        return ResumePoint(
            resumeSec = resumeSec,
            resumeStamp = resumeStamp,
            wasFinishedOnOpen = wasFinishedOnOpen,
        )
    }
}
