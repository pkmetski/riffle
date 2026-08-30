package com.riffle.app.di

import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.dsl.module

/** No-op repository used in harness tests: never hits the network, never shows an update dialog. */
class FakeAppUpdateRepository : AppUpdateRepository {
    override suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult =
        UpdateCheckResult.UpToDate

    override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> = emptyFlow()

    override fun sweepStaleApks() = Unit

    override suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo> = emptyList()
}

val testAppUpdateKoinModule = module {
    single<AppUpdateRepository> { FakeAppUpdateRepository() }
}
