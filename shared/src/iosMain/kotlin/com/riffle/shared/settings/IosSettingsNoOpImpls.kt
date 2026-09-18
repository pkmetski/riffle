package com.riffle.shared.settings

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.ReadaloudReview
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import com.riffle.core.domain.localfiles.LocalFilesFolderHealthCheckerInterface
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

internal object IosNoOpReadaloudReviewRepository : ReadaloudReviewRepository {
    override fun observeReview(storytellerSourceId: String, absSourceId: String?): Flow<ReadaloudReview> =
        flowOf(ReadaloudReview(pending = emptyList(), unmatched = emptyList(), confirmed = emptyList()))
    override suspend fun searchAbsItems(
        absSourceId: String,
        query: String,
        filter: com.riffle.core.domain.AbsFormatFilter,
    ): List<com.riffle.core.domain.AbsPickerItem> = emptyList()
}

internal object IosNoOpAnnotationSyncConfigStore : AnnotationSyncConfigStore {
    override fun observe(): StateFlow<AnnotationSyncConfig?> = MutableStateFlow(null)
    override suspend fun save(config: AnnotationSyncConfig) {}
    override suspend fun clear() {}
}

internal object IosNoOpLocalFilesFolderHealthChecker : LocalFilesFolderHealthCheckerInterface {
    override fun healthFor(treeUris: Collection<String>): Map<String, Boolean> = emptyMap()
}
