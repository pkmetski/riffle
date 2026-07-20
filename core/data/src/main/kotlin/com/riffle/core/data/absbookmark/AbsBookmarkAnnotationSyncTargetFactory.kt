package com.riffle.core.data.absbookmark

import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.AbsWebSourceDescriptor
import com.riffle.core.network.AbsBookmarkApi
import javax.inject.Inject

/**
 * Builds an [AbsBookmarkAnnotationSyncTarget] for a single ABS [Source].
 *
 * Returns null when the source is ineligible (not ABS, missing token, missing `absUserId`) so the
 * holder can quietly skip it — the same source may become eligible later after the user re-auths.
 */
class AbsBookmarkAnnotationSyncTargetFactory @Inject constructor(
    private val absBookmarkApi: AbsBookmarkApi,
    private val tokenStorage: TokenStorage,
) {
    suspend fun create(source: Source): AbsBookmarkAnnotationSyncTarget? {
        if (source.type != SourceType.ABS) return null
        if (source.serverType != ServerType.AUDIOBOOKSHELF) return null
        // Temporary rollout gate: only these ABS usernames get the new bookmark-piggyback
        // transport. Others fall through to WebDAV (or no sync) via the holder's normal path.
        // TODO(#TBD): remove the allow-list once the feature has soaked and Settings exposes
        // an explicit opt-in / kill-switch.
        if (source.username.trim().lowercase() !in ALLOWED_USERNAMES) return null
        val absUserId = source.absUserId?.takeIf { it.isNotBlank() } ?: return null
        val token = tokenStorage.getToken(source.id) ?: return null
        val namespace = "${AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX}$absUserId"
        return AbsBookmarkAnnotationSyncTarget(
            baseUrl = source.url.value,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
            accountNamespace = namespace,
            api = absBookmarkApi,
        )
    }

    private companion object {
        val ALLOWED_USERNAMES: Set<String> = setOf("plamen", "test")
    }
}
