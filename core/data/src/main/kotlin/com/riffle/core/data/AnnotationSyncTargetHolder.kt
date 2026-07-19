package com.riffle.core.data

import com.riffle.core.data.absbookmark.AbsBookmarkAnnotationSyncTargetFactory
import com.riffle.core.data.absbookmark.CompositeAnnotationSyncTarget
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.AbsWebSourceDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Holds the currently-active [AnnotationSyncTarget], rebuilt whenever WebDAV config OR the list of
 * ABS Sources changes.
 *
 * **Composition rules (ADR 0047):**
 * - WebDAV configured → one WebDAV child servicing every namespace.
 * - Each ABS Source with an accessible token and `absUserId` → one ABS-bookmark child, namespace-
 *   scoped to `abs_<absUserId>`. Komga-namespaced books are not routed to any ABS-bookmark child.
 * - Zero children → `current() == null` (annotation sync is off).
 * - One child → surfaced directly (no `CompositeAnnotationSyncTarget` wrapper).
 * - Two or more children → a [CompositeAnnotationSyncTarget] that fans out writes and unions reads.
 *
 * Building an ABS child requires a suspend call to [tokenStorage.getToken]; sources without a
 * stored token are silently skipped (the target rebuilds on the next config change, so a re-auth
 * flips them on).
 */
class AnnotationSyncTargetHolder(
    private val configStore: AnnotationSyncConfigStore,
    private val webDavFactory: WebDavAnnotationSyncTargetFactory,
    private val absBookmarkFactory: AbsBookmarkAnnotationSyncTargetFactory,
    private val sourceRepository: SourceRepository,
    scope: CoroutineScope,
) {
    /**
     * Seeded synchronously from the config store's current value so early consumers like
     * `EpubReaderViewModel.syncOnOpen` don't race the initial coroutine emission and see null
     * for the first few frames — the pre-composite holder was written this way and dropping the
     * seed was the exact regression the old kdoc warned about.
     *
     * The seed uses `runBlocking` — safe because the constructor already runs on a background
     * scope for DI and both `WebDavAnnotationSyncTargetFactory.create` and
     * `AbsBookmarkAnnotationSyncTargetFactory.create` are non-suspending / suspend-but-cheap
     * (SharedPreferences read for the token; no network).
     */
    private val state: MutableStateFlow<AnnotationSyncTarget?> = MutableStateFlow(
        runBlocking {
            buildTarget(
                webDavConfig = configStore.observe().value,
                sources = emptyList(),
            )
        },
    )

    init {
        scope.launch {
            combine(
                configStore.observe(),
                sourceRepository.observeAll(),
            ) { webDavConfig, sources -> webDavConfig to sources }
                .collectLatest { (webDavConfig, sources) ->
                    // `collectLatest` cancels an in-flight buildTarget when a newer config arrives,
                    // so a slow token read can never write a stale target on top of a fresh one.
                    state.value = buildTarget(webDavConfig, sources)
                }
        }
    }

    /**
     * Synchronous accessor used by [AnnotationSyncController] and friends. Returns null when no
     * target is currently configured; a mid-op rebuild does not invalidate any target reference a
     * caller has already captured.
     */
    fun current(): AnnotationSyncTarget? = state.value

    private suspend fun buildTarget(
        webDavConfig: com.riffle.core.domain.AnnotationSyncConfig?,
        sources: List<Source>,
    ): AnnotationSyncTarget? {
        val webDavChild = webDavConfig
            ?.let { webDavFactory.create(it) }
            ?.let {
                CompositeAnnotationSyncTarget.Child(
                    target = it,
                    serves = { _ -> true },
                    label = "webdav",
                )
            }

        val absChildren = sources
            .filter { it.type == SourceType.ABS && it.serverType == ServerType.AUDIOBOOKSHELF }
            .mapNotNull { source ->
                val target = absBookmarkFactory.create(source) ?: return@mapNotNull null
                val namespace = "${AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX}${source.absUserId!!}"
                CompositeAnnotationSyncTarget.Child(
                    target = target,
                    serves = { ns -> ns == namespace },
                    label = "abs:${source.id.take(8)}",
                )
            }

        val all = listOfNotNull(webDavChild) + absChildren
        return when (all.size) {
            0 -> null
            1 -> all.first().target
            else -> CompositeAnnotationSyncTarget(all)
        }
    }
}
