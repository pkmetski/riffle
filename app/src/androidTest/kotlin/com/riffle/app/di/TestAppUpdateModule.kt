package com.riffle.app.di

import com.riffle.core.data.di.modules.AppUpdateModule
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** No-op repository used in harness tests: never hits the network, never shows an update dialog. */
class FakeAppUpdateRepository @Inject constructor() : AppUpdateRepository {
    override suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult =
        UpdateCheckResult.UpToDate

    override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> = emptyFlow()

    override fun sweepStaleApks() = Unit

    override suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo> = emptyList()
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppUpdateModule::class],
)
abstract class TestAppUpdateModule {

    @Binds
    @Singleton
    abstract fun bindAppUpdateRepository(fake: FakeAppUpdateRepository): AppUpdateRepository
}
