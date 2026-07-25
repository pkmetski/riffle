package com.riffle.app.feature.update

import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private var releasesResult: List<ReleaseInfo> = emptyList()

    private val fakeRepo = object : AppUpdateRepository {
        override suspend fun checkForUpdate(currentVersionCode: Int) = UpdateCheckResult.UpToDate
        override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> = emptyFlow()
        override fun sweepStaleApks() = Unit
        override suspend fun listReleasesSince(sinceVersionCode: Int) = releasesResult
    }

    private fun makeViewModel() = ChangelogViewModel(fakeRepo)

    @Test
    fun `releases is empty before fetch completes`() = testScope.runTest {
        val vm = makeViewModel()
        assertTrue(vm.releases.value.isEmpty())
    }

    @Test
    fun `releases populated from listReleasesSince(0)`() = testScope.runTest {
        releasesResult = listOf(
            ReleaseInfo("2.0.0", 20000, "Big release", "https://x", 1000L),
            ReleaseInfo("1.9.0", 19000, "Patch", "https://x", 1000L),
        )
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.releases.value.size)
        assertEquals("2.0.0", vm.releases.value[0].versionName)
    }

    @Test
    fun `releases is empty when repo returns empty list`() = testScope.runTest {
        releasesResult = emptyList()
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.releases.value.isEmpty())
    }
}
