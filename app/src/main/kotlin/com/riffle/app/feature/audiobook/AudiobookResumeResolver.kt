package com.riffle.app.feature.audiobook

import com.riffle.core.domain.AudiobookPositionReconciler
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.common.Clock
import javax.inject.Inject

/**
 * Computes the position to start playback at when opening an audiobook.
 *
 * Extracted from [AudiobookPlayerViewModel] (issue #345, slice 2). Concentrates the last-update-
 * wins reconcile (ADR 0029), the offline "no tracked position" progress-fraction fallback, the
 * finished-book normalisation, and the readaloud→audiobook handoff override — all previously
 * inlined into the VM's ~60-line init prologue.
 *
 * Side-effects (persisting the local position + timestamp on a server-win, or on a handoff
 * arrival) stay inside this class so callers just get a [ResumePoint] out and don't touch the
 * store themselves.
 */
class AudiobookResumeResolver @Inject constructor(
    private val positionStore: AudiobookPositionStore,
    private val clock: Clock,
) {
    /**
     * Resolved playback resume.
     *
     * @property resumeSec book-absolute seconds the player should start at.
     * @property resumeStamp the timestamp associated with [resumeSec] — used to seed the follow
     *   loop's `localUpdatedAt` (so a genuinely-newer local listen leads, and a stale remote can't
     *   pull us back). Zero on the offline-with-bundle-only fallback path (where [resumeSec] came
     *   from `readingProgress`); non-zero when the resume came from a genuinely tracked position
     *   (reconciler decision or handoff override).
     */
    data class ResumePoint(
        val resumeSec: Double,
        val resumeStamp: Long,
    )

    /**
     * Compute the resume for a fresh player-open.
     *
     * @param startAtSec [AudiobookPlayerViewModel.startAtSec] value: `< 0` = normal open (use the
     *   reconciled/server/local resume); `>= 0` = readaloud→audiobook handoff (override with this
     *   value, stamp it fresh so it wins last-update-wins).
     */
    suspend fun resolve(
        sourceId: String,
        itemId: String,
        session: AudiobookSession,
        readingProgressFraction: Float,
        startAtSec: Double,
    ): ResumePoint {
        // Last-update-wins reconcile: mirrors the ebook reader. If the last local listen is newer
        // than ABS's record — e.g. a final flush was dropped at teardown — resume from it;
        // otherwise adopt the server position and stamp the local row so it does not re-push.
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

        // No tracked position at all (offline with only a bundle: no local listen row, no server
        // record) → fall back to the item's library progress so we resume near where the app shows
        // it. Not a "tracked" position, so callers only use it as an inbound-only resume floor.
        resumeSec = audiobookResumeSec(
            reconciledSec = resumeSec,
            hadTrackedPosition = reconciledStamp > 0L,
            readingProgressFraction = readingProgressFraction,
            durationSec = session.timeline.durationSec,
        )

        // A finished book (resume at the end) is unplayable if seeded there — ExoPlayer lands in
        // STATE_ENDED and play() is a no-op. Reopening it is a replay, so restart from 0. Only on
        // a normal open: the handoff below sets an explicit position that must not be reset.
        if (startAtSec < 0.0) {
            resumeSec = audiobookStartSec(resumeSec, session.timeline.durationSec)
        }

        // readaloud→audiobook swipe handoff: continue from exactly where the reader handed off,
        // overriding the store/server resume (which can lag the just-left listen position). Persist
        // it with a fresh stamp so it wins last-update-wins and isn't pulled back by a stale server.
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
        )
    }
}
