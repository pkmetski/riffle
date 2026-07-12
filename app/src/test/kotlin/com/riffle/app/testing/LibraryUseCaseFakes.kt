package com.riffle.app.testing

import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import com.riffle.core.domain.usecase.MarkReadAcrossDimensions
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.domain.usecase.RefreshCollections
import com.riffle.core.domain.usecase.RefreshLibraries
import com.riffle.core.domain.usecase.RefreshLibraryItems
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.domain.usecase.UpdateReadingProgress

/**
 * Default no-op use-case fakes shared by JVM ViewModel tests. Each test that needs to assert on a
 * specific use-case's effects substitutes a custom override; everything else gets a quiet stub so
 * the VM can be constructed without a Hilt graph.
 */

object NoopLibraryMutator : LibraryMutator {
    override suspend fun markItemOpened(itemId: String) = Unit
    override suspend fun updateReadingProgress(itemId: String, progress: Float) = Unit
    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
}

object NoopReadingSessionRepository : ReadingSessionRepository {
    override suspend fun syncProgress(itemId: String, payload: com.riffle.core.domain.SessionPayload) =
        com.riffle.core.domain.SyncSessionResult.Success
    override suspend fun runSyncCycle(itemId: String, payload: com.riffle.core.domain.SessionPayload) =
        com.riffle.core.domain.ProgressSyncCycleResult.InSync
    override suspend fun markFinished(itemId: String, finished: Boolean) = Unit
    override suspend fun touchOpenTimestamp(itemId: String) = Unit
}

object NoopLibraryRefresher : LibraryRefresher {
    override suspend fun refreshLibraries() = LibraryRefreshResult.Success
    override suspend fun refreshLibraryItems(libraryId: String) = LibraryRefreshResult.Success
    override suspend fun refreshSeries(libraryId: String) = LibraryRefreshResult.Success
    override suspend fun refreshCollections(libraryId: String) = LibraryRefreshResult.Success
}

class NoopUpdateReadingProgress(
    val calls: MutableList<Pair<String, Float>> = mutableListOf(),
) : UpdateReadingProgress(NoopLibraryMutator) {
    override suspend fun invoke(itemId: String, progress: Float) { calls += itemId to progress }
}

class NoopRecordItemOpened(
    val openedIds: MutableList<String> = mutableListOf(),
) : RecordItemOpened(NoopLibraryMutator, NoopReadingSessionRepository) {
    override suspend fun invoke(itemId: String) { openedIds += itemId }
}

class NoopMarkReadAcrossDimensions : MarkReadAcrossDimensions(
    NoopLibraryMutator,
    NoopReadingSessionRepository,
    NoopReadaloudLinkRepository,
    NoopServerRepository,
) {
    val calls = mutableListOf<Pair<String, Boolean>>()
    override suspend fun invoke(itemId: String, finished: Boolean) { calls += itemId to finished }
}

class NoopRefreshLibraries(
    private val result: LibraryRefreshResult = LibraryRefreshResult.Success,
) : RefreshLibraries(NoopLibraryRefresher) {
    var calls = 0
    override suspend fun invoke(): LibraryRefreshResult { calls++; return result }
}

class NoopRefreshLibraryItems(
    private val result: LibraryRefreshResult = LibraryRefreshResult.Success,
) : RefreshLibraryItems(
    NoopLibraryRefresher,
    object : StorytellerReadaloudCacheSyncer { override suspend fun syncStale() = Unit },
    object : ReadaloudLinkReconciler { override suspend fun reconcileLinks() = Unit },
    TestApplicationScope(kotlinx.coroutines.GlobalScope),
) {
    var calls = 0
    override suspend fun invoke(libraryId: String): LibraryRefreshResult { calls++; return result }
}

class NoopRefreshSeries(
    private val result: LibraryRefreshResult = LibraryRefreshResult.Success,
) : RefreshSeries(NoopLibraryRefresher) {
    var calls = 0
    override suspend fun invoke(libraryId: String): LibraryRefreshResult { calls++; return result }
}

class NoopRefreshCollections(
    private val result: LibraryRefreshResult = LibraryRefreshResult.Success,
) : RefreshCollections(NoopLibraryRefresher) {
    var calls = 0
    override suspend fun invoke(libraryId: String): LibraryRefreshResult { calls++; return result }
}

private object NoopReadaloudLinkRepository : com.riffle.core.domain.ReadaloudLinkRepository {
    override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.ReadaloudLink>())
    override fun observeLinkedAbsItemIds() = kotlinx.coroutines.flow.flowOf(emptySet<String>())
    override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String) = null
    override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) = emptyList<com.riffle.core.domain.ReadaloudLink>()
    override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) = Unit
    override suspend fun countForSource(sourceId: String) = 0
    override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: com.riffle.core.domain.AudiobookIdentityResult) = Unit
}

private object NoopServerRepository : com.riffle.core.domain.SourceRepository {
    override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.Source>())
    override suspend fun getActive(): com.riffle.core.domain.Source? = null
    override suspend fun authenticate(
        url: com.riffle.core.domain.SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: com.riffle.core.domain.ServerType,
        sourceType: com.riffle.core.domain.SourceType,
    ): com.riffle.core.domain.AuthenticateResult = throw UnsupportedOperationException()
    override suspend fun commit(
        pending: com.riffle.core.domain.PendingSource,
        hiddenLibraryIds: Set<String>,
    ): com.riffle.core.domain.CommitSourceResult = throw UnsupportedOperationException()
    override suspend fun setActive(sourceId: String) = Unit
    override suspend fun remove(sourceId: String) = Unit
    override suspend fun getSourceVersion(sourceId: String): String? = null
}
