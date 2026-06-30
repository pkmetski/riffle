package com.riffle.core.domain.usecase

import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Refresh one library's items, then fan out two background side-effects that aren't on the critical
 * path:
 *
 * 1. [StorytellerReadaloudCacheSyncer.syncStale] keeps the Storyteller catalogue fresh.
 * 2. [ReadaloudLinkReconciler.reconcileLinks] re-runs the auto-matcher against the new state.
 *
 * Both fire on the application scope so the caller returns as soon as the Room write lands.
 */
open class RefreshLibraryItems @Inject constructor(
    private val refresher: LibraryRefresher,
    private val storytellerSyncer: StorytellerReadaloudCacheSyncer,
    private val readaloudReconciler: ReadaloudLinkReconciler,
    applicationScope: ApplicationScope,
) {
    private val backgroundScope = applicationScope.coroutineScope

    open suspend operator fun invoke(libraryId: String): LibraryRefreshResult {
        val result = refresher.refreshLibraryItems(libraryId)
        if (result is LibraryRefreshResult.Success) {
            backgroundScope.launch {
                storytellerSyncer.syncStale()
                readaloudReconciler.reconcileLinks()
            }
        }
        return result
    }
}
