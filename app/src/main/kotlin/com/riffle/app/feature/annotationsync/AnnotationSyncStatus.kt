package com.riffle.app.feature.annotationsync

import com.riffle.core.data.CycleOutcome
import com.riffle.core.domain.AnnotationSyncConfig

/**
 * Single source of truth for the WebDAV sync "kind" (Local / Synced / Pending / Error) rendered by
 * the main Settings row ([com.riffle.app.feature.settings.SettingsViewModel.annotationSyncRow]) and
 * the AddServer WebDAV banner
 * ([com.riffle.app.feature.server.AddServerViewModel.webdavBanner]).
 *
 * Both surfaces observe the same singleton [com.riffle.core.data.AnnotationSyncStatusStore] and the
 * same pending-book count, and both call this function to decide their badge/kind — so the two
 * views cannot contradict each other. Adding a new rule (e.g. degrading Success → Pending while
 * books are unsynced) is done here so both places pick it up.
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
