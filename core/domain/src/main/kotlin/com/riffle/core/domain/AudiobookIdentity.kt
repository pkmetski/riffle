package com.riffle.core.domain

import kotlin.math.abs
import kotlin.math.max

/**
 * A recording's identity as Riffle can cheaply observe it: the total byte size, the total
 * duration, and the per-track durations. For the ABS side these come from the library item's
 * audio files; for the Storyteller side from the *ingested-source* audiobook record in
 * `/api/v2/books/{id}` (the original file, not the re-split bundle). See [ADR 0028].
 */
data class AudiobookFingerprint(
    val fileSizeBytes: Long,
    val durationSec: Double,
    val trackDurationsSec: List<Double>,
)

/**
 * Decides whether the ABS audiobook is the same recording Storyteller aligned against — the gate
 * that makes the streaming path safe. Streaming is only taken when this returns true, so a
 * name-matched-but-different audiobook never silently mis-syncs the highlight (ADR 0028).
 */
object AudiobookIdentity {

    /** Two recordings whose total durations and per-track durations agree to within this are the same. */
    private const val DURATION_TOLERANCE_SEC = 2.0

    /** A re-mux can strip tags; allow a small relative drift in byte size before calling it a different file. */
    private const val FILE_SIZE_RELATIVE_TOLERANCE = 0.01

    fun matches(storytellerSource: AudiobookFingerprint, absAudiobook: AudiobookFingerprint): Boolean {
        if (!fileSizeClose(storytellerSource.fileSizeBytes, absAudiobook.fileSizeBytes)) return false
        if (abs(storytellerSource.durationSec - absAudiobook.durationSec) > DURATION_TOLERANCE_SEC) return false
        val a = storytellerSource.trackDurationsSec
        val b = absAudiobook.trackDurationsSec
        if (a.size != b.size) return false
        return a.zip(b).all { (x, y) -> abs(x - y) <= DURATION_TOLERANCE_SEC }
    }

    private fun fileSizeClose(a: Long, b: Long): Boolean {
        if (a == b) return true
        val larger = max(a, b).toDouble()
        if (larger == 0.0) return true
        return abs(a - b) / larger <= FILE_SIZE_RELATIVE_TOLERANCE
    }
}
