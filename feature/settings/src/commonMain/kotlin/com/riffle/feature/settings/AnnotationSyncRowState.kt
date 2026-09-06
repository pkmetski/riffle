package com.riffle.feature.settings

import com.riffle.core.sync.CycleOutcome

/** UI state for the at-a-glance "WebDAV" row in Settings (ADR 0043). */
data class AnnotationSyncRowState(
    val badge: Badge,
    val headline: String,
    val sub: AnnotationSyncSubtitle,
    val subTone: Tone,
) {
    enum class Badge { Local, Synced, Pending, Error }
    enum class Tone { Normal, Pending, Error }
}

/**
 * Platform-agnostic description of the WebDAV row subtitle. Platform UI layers (Android Compose,
 * iOS SwiftUI) resolve each variant to a localised string. Replaces the previous context.getString()
 * calls inside SettingsViewModel so the VM can live in commonMain without android.content.Context.
 */
sealed class AnnotationSyncSubtitle {
    /** WebDAV is not configured — only annotation-local mode is active. */
    data object NotConfigured : AnnotationSyncSubtitle()

    /** Configured but no sync cycle has completed yet. */
    data object WaitingForFirstSync : AnnotationSyncSubtitle()

    /** Last cycle ended with an authentication failure. */
    data object AuthFailed : AnnotationSyncSubtitle()

    /** Last cycle ended with a TLS/certificate error. */
    data object TlsError : AnnotationSyncSubtitle()

    /** Last cycle ended with an HTTP server error. */
    data class HttpError(val code: Int) : AnnotationSyncSubtitle()

    /** Last cycle ended with an unknown/generic failure. */
    data object SyncFailed : AnnotationSyncSubtitle()

    /** Device is offline and there are un-pushed local books. */
    data class BooksPendingOffline(val count: Int) : AnnotationSyncSubtitle()

    /** Device is offline and there are no pending books. */
    data object Offline : AnnotationSyncSubtitle()

    /** Fully synced — shows the configured user identity. */
    data class Synced(val identity: String?) : AnnotationSyncSubtitle()
}

internal fun deriveSubtitle(
    config: com.riffle.core.domain.AnnotationSyncConfig?,
    outcome: CycleOutcome,
    pendingCount: Int,
): AnnotationSyncSubtitle {
    val identity = config?.let { "${it.username}@${shortHost(it.baseUrl)}" }
    return when {
        config == null -> AnnotationSyncSubtitle.NotConfigured
        // NeverRun outranks a positive pending count — keep "Waiting for first sync…" for a
        // freshly-configured install rather than showing "N book(s) pending".
        outcome is CycleOutcome.NeverRun -> AnnotationSyncSubtitle.WaitingForFirstSync
        outcome is CycleOutcome.Failed.Auth -> AnnotationSyncSubtitle.AuthFailed
        outcome is CycleOutcome.Failed.Tls -> AnnotationSyncSubtitle.TlsError
        outcome is CycleOutcome.Failed.Server -> AnnotationSyncSubtitle.HttpError(outcome.code)
        outcome is CycleOutcome.Failed.Unknown -> AnnotationSyncSubtitle.SyncFailed
        outcome is CycleOutcome.Failed.Network && pendingCount > 0 ->
            AnnotationSyncSubtitle.BooksPendingOffline(pendingCount)
        outcome is CycleOutcome.Failed.Network -> AnnotationSyncSubtitle.Offline
        pendingCount > 0 -> AnnotationSyncSubtitle.BooksPendingOffline(pendingCount)
        else -> AnnotationSyncSubtitle.Synced(identity)
    }
}

private fun shortHost(rawUrl: String): String =
    rawUrl.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
