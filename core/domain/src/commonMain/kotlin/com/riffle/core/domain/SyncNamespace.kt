package com.riffle.core.domain

/**
 * Annotation-sync namespace resolution for a [Source] (#529).
 *
 * Every source that participates in annotation sync produces a cross-device-stable string —
 * shared by every device signed into the *same* remote account — that keys the source's
 * annotation files on the sync target (WebDAV today, per ADR 0035). Two devices resolve to the
 * same [Configured.value] iff they should discover each other's annotation files.
 *
 * The namespace is a plain opaque [String] to the transport ([AnnotationSyncTarget]); the shape
 * (`abs<userId>` vs. `komga_<userId>` vs. anything else future descriptors want) is entirely a
 * [WebSourceDescriptor.syncNamespaceFor] concern. New source kinds don't require any change to
 * the sync controller or the sweep — they only implement the descriptor hook.
 *
 * Sources that genuinely have no cross-device identity (pure local, anonymous public catalogs
 * like Gutenberg/Chitanka) return [LocalOnly] so the UI can render a clear "local-only" state
 * instead of silently no-opping.
 */
sealed interface SyncNamespace {
    /**
     * Cross-device sync is available for this source. [value] is the opaque namespace passed to
     * [AnnotationSyncTarget] — treated as an atomic key by every downstream consumer.
     */
    data class Configured(val value: String) : SyncNamespace

    /**
     * This source has a cross-device identity in principle, but the remote user id hasn't been
     * fetched yet (legacy row created before the column existed, or a fresh install where the
     * add-source handshake didn't stash the id). Callers ask [SourceRepository.ensureSyncNamespace]
     * to make the network call and persist the id, which then resolves to [Configured].
     */
    object PendingRemoteId : SyncNamespace

    /**
     * This source is not eligible for cross-device sync. [reason] is a short user-facing string
     * (rendered in the annotation-sync status UI). Examples: "Local files stay on this device."
     * or "Public-domain catalog — no account to sync against."
     */
    data class LocalOnly(val reason: String) : SyncNamespace
}
