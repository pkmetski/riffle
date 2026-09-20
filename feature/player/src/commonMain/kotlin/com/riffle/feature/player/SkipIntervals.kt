package com.riffle.feature.player

import com.riffle.core.domain.ListeningPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * How far the transport controls jump, in seconds — the user's stored Listening preferences
 * projected onto the one shape every player surface needs.
 *
 * "Transport controls" means all of them: the in-app player's ⏪/⏩ buttons, iOS's
 * `MPRemoteCommandCenter` skip commands (lock screen, Control Centre, CarPlay) and Android's
 * media-notification rewind/forward buttons. All three are driven from
 * [ListeningPreferencesStore.skipIntervalSeconds] / [ListeningPreferencesStore.rewindIntervalSeconds]
 * through [transportSkipIntervals], so there is exactly one place the preference is read and no
 * per-platform copy of the numbers.
 */
data class SkipIntervals(val forwardSec: Int, val backwardSec: Int) {

    fun forwardTargetSec(currentSec: Double, durationSec: Double): Double {
        val target = currentSec + forwardSec
        return if (durationSec > 0.0) target.coerceAtMost(durationSec) else target
    }

    fun backwardTargetSec(currentSec: Double): Double =
        (currentSec - backwardSec).coerceAtLeast(0.0)

    companion object {
        /**
         * Bounds of the Settings steppers. Clamping here as well as in the UI keeps a value that
         * predates a bounds change — or one written by the other platform — from reaching the
         * platform transport, where an out-of-range interval is silently ignored rather than
         * rejected.
         */
        const val MIN_SECONDS = 5
        const val MAX_SECONDS = 120

        val DEFAULT = SkipIntervals(
            forwardSec = ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS,
            backwardSec = ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS,
        )

        fun of(forwardSec: Int, backwardSec: Int): SkipIntervals =
            SkipIntervals(clamp(forwardSec), clamp(backwardSec))

        fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
    }
}

/**
 * The intervals the platform transport should advertise right now, re-emitted whenever the user
 * changes either stepper. [distinctUntilChanged] keeps a rewrite of an unchanged preference from
 * churning the lock-screen command centre / media notification.
 */
fun ListeningPreferencesStore.transportSkipIntervals(): Flow<SkipIntervals> =
    combine(skipIntervalSeconds, rewindIntervalSeconds) { forward, backward ->
        SkipIntervals.of(forward, backward)
    }.distinctUntilChanged()

/**
 * Pushes the stored intervals into [AudioPlayerInterface.setSkipIntervals] and keeps pushing for as
 * long as it is collected — the platform transport is configured once per player open and would
 * otherwise keep whatever interval was current at setup, which is exactly how iOS's lock screen
 * ended up permanently on 30 s / 15 s.
 *
 * Suspends forever; call it from the player ViewModel's scope.
 */
suspend fun AudioPlayerInterface.followSkipIntervals(store: ListeningPreferencesStore) {
    store.transportSkipIntervals().collect { setSkipIntervals(it) }
}

/**
 * Where playback resumes from after a pause, honouring "Rewind on Resume". Shared by the
 * play/pause button and by the resume position the player opens at, which used to spell the same
 * rule out twice.
 */
fun resumePositionSec(currentSec: Double, rewindOnResumeSec: Double): Double =
    if (rewindOnResumeSec > 0.0) (currentSec - rewindOnResumeSec).coerceAtLeast(0.0) else currentSec

/** Accessibility / notification label for the skip-forward control, e.g. `"Forward 30 seconds"`. */
fun skipForwardLabel(seconds: Int): String = "Forward $seconds seconds"

/** Accessibility / notification label for the rewind control, e.g. `"Rewind 15 seconds"`. */
fun skipBackwardLabel(seconds: Int): String = "Rewind $seconds seconds"

/**
 * Which numbered skip glyph an interval gets. Android's media notification only ships artwork for
 * 5 / 10 / 15 / 30 s and falls back to an unnumbered arrow for anything else; the buckets mirror
 * Media3's own (private) `CommandButton.getIconForPlayerCommand` thresholds so a configured 20 s
 * skip shows the 30 s glyph there exactly as it would if Media3 picked the icon itself.
 */
enum class SkipIconBucket {
    SEC_5,
    SEC_10,
    SEC_15,
    SEC_30,
    GENERIC,
    ;

    companion object {
        fun of(seconds: Int): SkipIconBucket = when (seconds) {
            in 3..7 -> SEC_5
            in 8..12 -> SEC_10
            in 13..19 -> SEC_15
            in 20..39 -> SEC_30
            else -> GENERIC
        }
    }
}
