package com.riffle.shared.settings

import com.riffle.core.domain.localfiles.LocalFilesFolderHealthCheckerInterface
import com.riffle.core.domain.localfiles.LocalFilesFolderRepositoryInterface
import com.riffle.core.domain.localfiles.LocalFilesScannerInterface
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudReview
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import com.riffle.core.models.CrashReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

internal object IosNoOpCrashReportRepository : CrashReportRepository {
    override fun listCrashReports(): List<CrashReport> = emptyList()
    override fun resolveReportFilePaths(ids: List<String>): List<String> = emptyList()
    override fun clearAllCrashReports() {}
}

internal object IosNoOpAppUpdateRepository : AppUpdateRepository {
    override suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult = UpdateCheckResult.UpToDate
    override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> = flowOf()
    override fun sweepStaleApks() {}
    override suspend fun listReleasesSince(sinceVersionCode: Int): List<com.riffle.core.domain.ReleaseInfo> = emptyList()
}

internal class IosNoOpAppUpdatePreferencesStore : AppUpdatePreferencesStore {
    override val autoUpdateEnabled: Flow<Boolean> = flowOf(false)
    override val ignoredVersionCode: Flow<Int> = flowOf(0)
    override suspend fun setAutoUpdateEnabled(value: Boolean) {}
    override suspend fun setIgnoredVersionCode(value: Int) {}
}

internal class IosNoOpWakeLockPreferencesStore : WakeLockPreferencesStore {
    override val keepScreenOn: Flow<Boolean> = flowOf(false)
    override suspend fun setKeepScreenOn(value: Boolean) {}
}

internal class IosNoOpVolumeKeyPreferencesStore : VolumeKeyPreferencesStore {
    override val volumeKeyNavigationEnabled: Flow<Boolean> = flowOf(false)
    override val invertVolumeKeys: Flow<Boolean> = flowOf(false)
    override suspend fun setVolumeKeyNavigationEnabled(value: Boolean) {}
    override suspend fun setInvertVolumeKeys(value: Boolean) {}
}

internal class IosNoOpListeningPreferencesStore : ListeningPreferencesStore {
    override val defaultPlaybackSpeed: Flow<Float> = flowOf(1f)
    override val skipIntervalSeconds: Flow<Int> = flowOf(30)
    override val rewindIntervalSeconds: Flow<Int> = flowOf(10)
    override val rewindOnResumeSeconds: Flow<Int> = flowOf(0)
    override suspend fun setDefaultPlaybackSpeed(speed: Float) {}
    override suspend fun setSkipIntervalSeconds(seconds: Int) {}
    override suspend fun setRewindIntervalSeconds(seconds: Int) {}
    override suspend fun setRewindOnResumeSeconds(seconds: Int) {}
}

internal class IosNoOpLibraryOrderPreferencesStore : LibraryOrderPreferencesStore {
    override fun libraryOrder(sourceId: String): Flow<List<String>> = flowOf(emptyList())
    override suspend fun setLibraryOrder(sourceId: String, orderedIds: List<String>) {}
}

internal class IosNoOpReadaloudPreferencesStore : ReadaloudPreferencesStore {
    override val preferences: Flow<ReadaloudPreferences> = flowOf(ReadaloudPreferences())
    override suspend fun update(prefs: ReadaloudPreferences) {}
}

internal object IosNoOpReadaloudReviewRepository : ReadaloudReviewRepository {
    override fun observeReview(storytellerSourceId: String, absSourceId: String?): Flow<ReadaloudReview> =
        flowOf(ReadaloudReview(pending = emptyList(), unmatched = emptyList(), confirmed = emptyList()))
    override suspend fun searchAbsItems(absSourceId: String, query: String, filter: com.riffle.core.domain.AbsFormatFilter): List<com.riffle.core.domain.AbsPickerItem> = emptyList()
}

internal class IosNoOpDeveloperOptionsRepository : DeveloperOptionsRepository {
    override val developerModeEnabled: Flow<Boolean> = flowOf(false)
    override suspend fun setDeveloperModeEnabled(enabled: Boolean) {}
    override suspend fun getGithubPat(): String? = null
    override suspend fun setGithubPat(pat: String?) {}
}

internal object IosNoOpAnnotationSyncConfigStore : AnnotationSyncConfigStore {
    override fun observe(): StateFlow<AnnotationSyncConfig?> = MutableStateFlow(null)
    override suspend fun save(config: AnnotationSyncConfig) {}
    override suspend fun clear() {}
}

internal object IosNoOpLocalFilesScannerInterface : LocalFilesScannerInterface {
    override suspend fun scan(sourceId: String) {}
}

internal object IosNoOpLocalFilesFolderRepository : LocalFilesFolderRepositoryInterface {
    override suspend fun removeFolder(sourceId: String, treeUri: String) {}
}

internal object IosNoOpLocalFilesFolderHealthChecker : LocalFilesFolderHealthCheckerInterface {
    override fun healthFor(treeUris: Collection<String>): Map<String, Boolean> = emptyMap()
}

internal class IosNoOpComicFormattingPreferencesStore : ComicFormattingPreferencesStore {
    override val preferences: Flow<ComicFormattingPreferences> = flowOf(ComicFormattingPreferences())
    override suspend fun update(prefs: ComicFormattingPreferences) {}
}

internal object IosNoOpLocalFilesFolderDao : LocalFilesFolderDao {
    override suspend fun upsert(entity: LocalFilesFolderEntity) {}
    override suspend fun forSource(sourceId: String): List<LocalFilesFolderEntity> = emptyList()
    override fun observeForSource(sourceId: String): Flow<List<LocalFilesFolderEntity>> = flowOf(emptyList())
    override suspend fun getByLibraryId(sourceId: String, libraryId: String): LocalFilesFolderEntity? = null
    override suspend fun delete(sourceId: String, treeUri: String) {}
}
