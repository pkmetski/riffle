package com.riffle.app.feature.update

import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangelogViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private var releasesResult: List<ReleaseInfo> = emptyList()

    private val fakeRepo = object : AppUpdateRepository {
        override suspend fun checkForUpdate(currentVersionCode: Int) = UpdateCheckResult.UpToDate
        override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> = emptyFlow()
        override fun sweepStaleApks() = Unit
        override suspend fun listReleasesSince(sinceVersionCode: Int) = releasesResult
    }

    private fun makeViewModel() = ChangelogViewModel(fakeRepo)

    @Test
    fun `state is Loading before fetch completes`() = runTest(testDispatcher) {
        val vm = makeViewModel()
        assertTrue(vm.state.value is ChangelogUiState.Loading)
    }

    @Test
    fun `state is Loaded with releases after fetch`() = runTest(testDispatcher) {
        releasesResult = listOf(
            ReleaseInfo("2.0.0", 20000, "Big release", "https://x", 1000L),
            ReleaseInfo("1.9.0", 19000, "Patch", "https://x", 1000L),
        )
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val loaded = vm.state.value as ChangelogUiState.Loaded
        assertEquals(2, loaded.releases.size)
        assertEquals("2.0.0", loaded.releases[0].versionName)
    }

    @Test
    fun `releaseUrl is preserved in loaded state so the UI can render a link`() = runTest(testDispatcher) {
        releasesResult = listOf(
            ReleaseInfo("2.0.0", 20000, "Notes", "https://x", 1000L, "https://github.com/pkmetski/riffle/releases/tag/v2.0.0"),
        )
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val loaded = vm.state.value as ChangelogUiState.Loaded
        assertEquals("https://github.com/pkmetski/riffle/releases/tag/v2.0.0", loaded.releases[0].releaseUrl)
    }

    @Test
    fun `state is Loaded with empty list when repo returns nothing`() = runTest(testDispatcher) {
        releasesResult = emptyList()
        val vm = makeViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val loaded = vm.state.value as ChangelogUiState.Loaded
        assertTrue(loaded.releases.isEmpty())
    }
}
