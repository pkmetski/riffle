package com.riffle.core.domain

/**
 * Per-[SourceType] hook that fetches this source kind's stable cross-device user identity from
 * the remote server (#529). Consumed by [SourceRepository.ensureSyncNamespace] whenever a
 * source's descriptor returns [SyncNamespace.PendingRemoteId] — the repository invokes the
 * resolver keyed by [SourceType], persists the returned id into `Source.absUserId`, and
 * re-evaluates the descriptor to produce a [SyncNamespace.Configured].
 *
 * Bind implementations via Hilt multibinding keyed by [SourceType]; the repository injects the
 * `Map<SourceType, @JvmSuppressWildcards RemoteUserIdResolver>` and dispatches accordingly. A
 * new server-backed source becomes sync-eligible by (a) overriding
 * [WebSourceDescriptor.syncNamespaceFor], and (b) contributing a [RemoteUserIdResolver] to the
 * multibinding — no changes to `AnnotationSyncController`, the sweep, or the reader required.
 */
interface RemoteUserIdResolver {
    /**
     * Resolve the currently-authenticated user's stable id on this source, or null on any
     * network/parse failure (offline, server down, expired token). A null return leaves the
     * source in [SyncNamespace.PendingRemoteId] — the caller retries on the next open.
     */
    suspend fun resolve(source: Source, token: String): String?
}
