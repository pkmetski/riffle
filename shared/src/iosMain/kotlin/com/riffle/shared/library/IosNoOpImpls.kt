package com.riffle.shared.library

import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryFilterPreferences
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import com.riffle.core.models.AudiobookBookmark
import com.riffle.core.models.AudiobookIdentityResult
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.ScreenDimensionBucket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

internal object IosNoOpStorytellerSyncer : StorytellerReadaloudCacheSyncer {
    override suspend fun syncStale() {}
}

internal object IosNoOpReadaloudReconciler : ReadaloudLinkReconciler {
    override suspend fun reconcileLinks() {}
}

internal object IosNoOpApplicationScope : ApplicationScope {
    private val supervisor = SupervisorJob()
    override val coroutineScope: CoroutineScope = CoroutineScope(supervisor)
    override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
        coroutineScope.launch(block = block)
    override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
        block(coroutineScope)
    override fun scopeOn(dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(supervisor + dispatcher)
}

internal class IosNoOpCoverGridDensityStore : CoverGridDensityStore {
    override val scale: Flow<Float> = flowOf(1f)
    override suspend fun setScale(value: Float) {}
    override fun scale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket): Flow<Float> = flowOf(1f)
    override suspend fun setScale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket, value: Float) {}
}

internal class IosNoOpLibraryFilterPreferencesStore : LibraryFilterPreferencesStore {
    override fun preferences(sourceId: String, libraryId: String): Flow<LibraryFilterPreferences> =
        flowOf(LibraryFilterPreferences())
    override suspend fun setSelectedFacetKey(sourceId: String, libraryId: String, key: String?) {}
    override suspend fun setNotStartedFilterActive(sourceId: String, libraryId: String, active: Boolean) {}
    override suspend fun setUnownedFilterActive(sourceId: String, libraryId: String, active: Boolean) {}
    override suspend fun setSortModeName(sourceId: String, libraryId: String, name: String?) {}
}

internal class IosNoOpAudiobookBookmarkStore : AudiobookBookmarkStore {
    override fun observe(sourceId: String, itemId: String): Flow<List<AudiobookBookmark>> = flowOf(emptyList())
    override fun observeForSource(sourceId: String): Flow<List<AudiobookBookmark>> = flowOf(emptyList())
    override fun observeHasUnsynced(sourceId: String, itemId: String): Flow<Boolean> = flowOf(false)
    override suspend fun add(sourceId: String, itemId: String, positionSec: Double, title: String, now: Long): String = ""
    override suspend fun rename(id: String, title: String, now: Long) {}
    override suspend fun delete(id: String, now: Long) {}
}

internal class IosNoOpReadaloudLinkRepository : ReadaloudLinkRepository {
    override fun observeAll(): Flow<List<ReadaloudLink>> = flowOf(emptyList())
    override fun observeLinkedAbsItemIds(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? = null
    override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> = emptyList()
    override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) {}
    override suspend fun countForSource(sourceId: String): Int = 0
    override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: AudiobookIdentityResult) {}
}

internal class IosNoOpAppThemeStore : AppThemeStore {
    override val appTheme: Flow<AppTheme> = flowOf(AppTheme.System)
    override suspend fun setAppTheme(value: AppTheme) {}
}

internal class IosNoOpFormattingPreferencesStore : FormattingPreferencesStore {
    override val preferences: Flow<FormattingPreferences> = flowOf(FormattingPreferences())
    override suspend fun update(preferences: FormattingPreferences) {}
    override suspend fun setCadencePlatformSupported(supported: Boolean) {}
}

internal class IosNoOpDownloadsRepository : DownloadsRepository {
    override fun getDownloadedArtifacts(): List<StoredItemArtifact> = emptyList()
    override fun getCachedArtifacts(): List<StoredItemArtifact> = emptyList()
    override fun sizeOf(sourceId: String, itemId: String): Long = 0L
    override suspend fun removeDownload(sourceId: String, itemId: String) {}
    override suspend fun removeCached(sourceId: String, itemId: String) {}
    override suspend fun removeAllDownloads() {}
    override suspend fun clearAllCached() {}
}