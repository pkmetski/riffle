package com.riffle.feature.settings

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.sync.CycleOutcome

/**
 * Single source of truth for the WebDAV sync "kind" rendered by the Settings row and the
 * AddServer WebDAV banner. Both surfaces observe the same singleton [com.riffle.core.sync.AnnotationSyncStatusStore]
 * and the same pending-book count, and both call this function — so the two views cannot contradict each other.
 */
enum class AnnotationSyncKind { Local, Synced, Pending, Error }

fun deriveAnnotationSyncKind(
    config: AnnotationSyncConfig?,
    outcome: CycleOutcome,
    pendingBookCount: Int,
): AnnotationSyncKind {
    if (config == null) return AnnotationSyncKind.Local
    return when (outcome) {
        CycleOutcome.NeverRun -> AnnotationSyncKind.Pending
        is CycleOutcome.Failed.Auth,
        is CycleOutcome.Failed.Tls,
        is CycleOutcome.Failed.Server,
        is CycleOutcome.Failed.Unknown -> AnnotationSyncKind.Error
        is CycleOutcome.Failed.Network -> AnnotationSyncKind.Pending
        is CycleOutcome.Success ->
            if (pendingBookCount > 0) AnnotationSyncKind.Pending else AnnotationSyncKind.Synced
    }
}
