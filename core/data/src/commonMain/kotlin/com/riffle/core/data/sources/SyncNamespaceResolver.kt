package com.riffle.core.data.sources

import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType

/**
 * The `SourceRepository.ensureSyncNamespace` algorithm (#529), shared by both platforms (#1101 —
 * iOS previously inherited the interface default and answered `LocalOnly` for every source, so
 * the annotation sweep had nothing it could push).
 *
 * Delegates to [WebSourceDescriptors]; on [SyncNamespace.PendingRemoteId] it calls the
 * source-kind-specific [RemoteUserIdResolver], persists the fetched id through [persistRemoteId]
 * so subsequent calls are a single DB lookup, and re-evaluates the descriptor.
 */
class SyncNamespaceResolver(
    private val sourceLookup: suspend (sourceId: String) -> Source?,
    private val tokenStorage: TokenStorage,
    private val remoteUserIdResolvers: Map<SourceType, RemoteUserIdResolver>,
    private val persistRemoteId: suspend (sourceId: String, remoteUserId: String) -> Unit,
) {
    suspend fun resolve(sourceId: String): SyncNamespace {
        val source = sourceLookup(sourceId)
            ?: return SyncNamespace.LocalOnly("Unknown source.")
        val descriptor = WebSourceDescriptors.forType(source.type)
            ?: return SyncNamespace.LocalOnly("No descriptor for source type ${source.type}.")
        val initial = descriptor.syncNamespaceFor(source)
        if (initial !is SyncNamespace.PendingRemoteId) return initial

        // Descriptor advertises cross-device identity but the remote user id hasn't been fetched
        // yet — dispatch to the per-SourceType resolver. Anonymous / local descriptors never
        // reach this branch (they return LocalOnly above), so a missing resolver here means a
        // sync-eligible source kind wasn't wired into the resolver map.
        val resolver = remoteUserIdResolvers[source.type]
            ?: return SyncNamespace.LocalOnly("No remote-id resolver registered for ${source.type}.")
        val token = tokenStorage.getToken(sourceId)
            ?: return SyncNamespace.PendingRemoteId
        val fetched = resolver.resolve(source, token)?.takeIf { it.isNotBlank() }
            ?: return SyncNamespace.PendingRemoteId
        persistRemoteId(sourceId, fetched)
        // Project the freshly-fetched id through the descriptor's dedicated hook instead of
        // synthesising a `source.copy(absUserId = fetched)` and re-invoking syncNamespaceFor —
        // avoids the double-eval and keeps the "how do I turn an id into a namespace" logic in
        // one method per descriptor.
        return descriptor.namespaceFromRemoteId(source, fetched)
    }
}
