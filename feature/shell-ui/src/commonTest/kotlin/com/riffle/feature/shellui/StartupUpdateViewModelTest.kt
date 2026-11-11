package com.riffle.feature.shellui

import com.riffle.core.common.Clock
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StartupUpdateViewModelTest {

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private class MutableClock(var timeMs: Long = 0L) : Clock {
        override fun nowMs(): Long = timeMs
        override fun nowNs(): Long = timeMs * 1_000_000
    }

    private fun releaseInfo(
        versionName: String,
        versionCode: Int,
        changelog: String = "Notes for $versionName",
        downloadUrl: String = "https://x/$versionName.apk",
    ) = ReleaseInfo(
        versionName = versionName,
        versionCode = versionCode,
        changelog = changelog,
        downloadUrl = downloadUrl,
        sizeBytes = 1000L,
    )

    private class MutableFakePrefs(
        autoEnabled: Boolean = true,
        seen: Int = 0,
    ) : AppUpdatePreferencesStore {
        private val autoFlow = MutableStateFlow(autoEnabled)
        private val betaFlow = MutableStateFlow(false)
        val seenFlow = MutableStateFlow(seen)
        override val autoUpdateEnabled: Flow<Boolean> = autoFlow
        override val betaUpdatesEnabled: Flow<Boolean> = betaFlow
        override val seenUpdateVersionCode: Flow<Int> = seenFlow
        override suspend fun setAutoUpdateEnabled(value: Boolean) { autoFlow.value = value }
        override suspend fun setBetaUpdatesEnabled(value: Boolean) { betaFlow.value = value }
        override suspend fun setSeenUpdateVersionCode(value: Int) { seenFlow.value = value }
    }

    private fun fakeRepo(releases: List<ReleaseInfo> = emptyList()): AppUpdateRepository =
        object : AppUpdateRepository {
            override suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult =
                UpdateCheckResult.UpToDate
            override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> =
                flowOf(UpdateDownloadState.Installing)
            override fun sweepStaleApks() {}
            override suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo> = releases
        }

    private fun viewModel(
        repo: AppUpdateRepository = fakeRepo(),
        prefs: AppUpdatePreferencesStore = MutableFakePrefs(),
        clock: Clock = MutableClock(),
    ) = StartupUpdateViewModel(
        appUpdateRepository = repo,
        appUpdatePreferencesStore = prefs,
        clock = clock,
        currentVersionCode = 0,
    )

    @Test
    fun `updateState is null when auto-update is disabled`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = MutableFakePrefs(autoEnabled = false),
        )
        advanceUntilIdle()

        assertNull(vm.updateState.value)
    }

    @Test
    fun `updateState is null when no releases are newer`() = runTest {
        val vm = viewModel(repo = fakeRepo(emptyList()))
        advanceUntilIdle()

        assertNull(vm.updateState.value)
    }

    @Test
    fun `updateState is null when newer release has no APK artifact`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600, downloadUrl = ""))),
        )
        advanceUntilIdle()

        assertNull(vm.updateState.value)
    }

    @Test
    fun `updateState is non-null when update is available`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600, "Release notes"))),
        )
        advanceUntilIdle()

        val state = vm.updateState.value
        assertNotNull(state)
        assertEquals("1.6.0", state.update.versionName)
        assertEquals("Release notes", state.latestChangelog)
    }

    @Test
    fun `showDot is true when updateState is non-null and version exceeds seen`() = runTest {
        val prefs = MutableFakePrefs(seen = 0)
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = prefs,
        )
        advanceUntilIdle()

        assertTrue(vm.showDot.value)
    }

    @Test
    fun `showDot is false when seenVersionCode equals update versionCode`() = runTest {
        val prefs = MutableFakePrefs(seen = 10600)
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = prefs,
        )
        advanceUntilIdle()

        assertFalse(vm.showDot.value)
    }

    @Test
    fun `markUpdateSeen writes seenVersionCode and showDot becomes false`() = runTest {
        val prefs = MutableFakePrefs(seen = 0)
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = prefs,
        )
        advanceUntilIdle()
        assertTrue(vm.showDot.value)

        vm.markUpdateSeen()
        advanceUntilIdle()

        assertFalse(vm.showDot.value)
        assertEquals(10600, prefs.seenFlow.first())
    }

    @Test
    fun `showDot becomes true again when newer version appears after markUpdateSeen`() = runTest {
        val prefs = MutableFakePrefs(seen = 0)
        val releasesFlow = MutableStateFlow(listOf(releaseInfo("1.6.0", 10600)))
        val clock = MutableClock()
        val repo = object : AppUpdateRepository {
            override suspend fun checkForUpdate(currentVersionCode: Int) = UpdateCheckResult.UpToDate
            override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> = flowOf(UpdateDownloadState.Installing)
            override fun sweepStaleApks() {}
            override suspend fun listReleasesSince(sinceVersionCode: Int) = releasesFlow.value
        }
        val vm = viewModel(repo = repo, prefs = prefs, clock = clock)
        advanceUntilIdle()

        vm.markUpdateSeen()
        advanceUntilIdle()
        assertFalse(vm.showDot.value)

        clock.timeMs = StartupUpdateViewModel.AUTO_CHECK_INTERVAL_MS + 1
        releasesFlow.value = listOf(releaseInfo("1.7.0", 10700))
        vm.checkNow()
        advanceUntilIdle()

        assertTrue(vm.showDot.value)
    }

    @Test
    fun `showUpdateSheet sets showSheet to true`() = runTest {
        val vm = viewModel(repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))))
        advanceUntilIdle()

        vm.showUpdateSheet()

        assertTrue(vm.showSheet.value)
    }

    @Test
    fun `dismissSheet sets showSheet to false when not downloading`() = runTest {
        val vm = viewModel(repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))))
        advanceUntilIdle()
        vm.showUpdateSheet()
        assertTrue(vm.showSheet.value)

        vm.dismissSheet()

        assertFalse(vm.showSheet.value)
    }

    @Test
    fun `dismissSheet does nothing while download is in progress`() = runTest {
        val neverCompletes = flow<UpdateDownloadState> {
            emit(UpdateDownloadState.Downloading(0))
            kotlinx.coroutines.awaitCancellation()
        }
        val repo = object : AppUpdateRepository {
            override suspend fun checkForUpdate(currentVersionCode: Int) = UpdateCheckResult.UpToDate
            override fun downloadAndInstall(update: AvailableUpdate) = neverCompletes
            override fun sweepStaleApks() {}
            override suspend fun listReleasesSince(sinceVersionCode: Int) = emptyList<ReleaseInfo>()
        }
        val vm = viewModel(repo = repo)
        vm.showUpdateSheet()
        vm.startUpdate(AvailableUpdate("1.6.0", 10600, "https://x/1.6.0.apk", 1000L))
        advanceUntilIdle()

        vm.dismissSheet()

        assertTrue(vm.showSheet.value)
    }

    @Test
    fun `sheet auto-dismisses after installer is handed off`() = runTest {
        val vm = viewModel(repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))))
        advanceUntilIdle()
        vm.showUpdateSheet()
        assertTrue(vm.showSheet.value)

        vm.startUpdate(AvailableUpdate("1.6.0", 10600, "https://x/1.6.0.apk", 1000L))
        advanceUntilIdle()

        assertFalse(vm.showSheet.value)
    }

    @Test
    fun `prerelease flag on ReleaseInfo is propagated to AvailableUpdate in updateState`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(
                listOf(
                    ReleaseInfo(
                        versionName = "1.5.0-beta.1",
                        versionCode = 10500,
                        changelog = "",
                        downloadUrl = "https://x/beta.apk",
                        sizeBytes = 1000L,
                        prerelease = true,
                    )
                )
            ),
        )
        advanceUntilIdle()

        val state = vm.updateState.value
        assertNotNull(state)
        assertTrue(state.update.prerelease)
    }

    @Test
    fun `updateState skips releases without APK and offers next available`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(
                listOf(
                    releaseInfo("1.6.0", 10600, downloadUrl = ""),
                    releaseInfo("1.5.1", 10501, downloadUrl = "https://x/1.5.1.apk"),
                ),
            ),
        )
        advanceUntilIdle()

        val state = vm.updateState.value
        assertNotNull(state)
        assertEquals("1.5.1", state.update.versionName)
    }

    @Test
    fun `isDevVersionName returns true for dash-dev suffix and false for real versions`() {
        assertTrue(isDevVersionName("0.0.0-dev"))
        assertTrue(isDevVersionName("1.2.3-dev"))
        assertFalse(isDevVersionName("1.2.3"))
        assertFalse(isDevVersionName("0.0.0-ci"))
        assertFalse(isDevVersionName("1.0.0-rc1"))
    }
}
