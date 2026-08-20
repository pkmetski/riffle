package com.riffle.app.feature.update

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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupUpdateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class MutableClock(var timeMs: Long = 0L) : Clock {
        override fun nowMs(): Long = timeMs
        override fun nowNs(): Long = timeMs * 1_000_000
    }

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun releaseInfo(
        versionName: String,
        versionCode: Int,
        changelog: String = "",
        downloadUrl: String = "https://x/$versionName.apk",
    ) = ReleaseInfo(
        versionName = versionName,
        versionCode = versionCode,
        changelog = changelog,
        downloadUrl = downloadUrl,
        sizeBytes = 1000L,
    )

    private fun fakePrefs(autoEnabled: Boolean = true, ignored: Int = 0): AppUpdatePreferencesStore {
        val autoFlow = MutableStateFlow(autoEnabled)
        val ignoredFlow = MutableStateFlow(ignored)
        return object : AppUpdatePreferencesStore {
            override val autoUpdateEnabled: Flow<Boolean> = autoFlow
            override val ignoredVersionCode: Flow<Int> = ignoredFlow
            override suspend fun setAutoUpdateEnabled(value: Boolean) { autoFlow.value = value }
            override suspend fun setIgnoredVersionCode(value: Int) { ignoredFlow.value = value }
        }
    }

    private fun fakeRepo(releases: List<ReleaseInfo> = emptyList()): AppUpdateRepository =
        object : AppUpdateRepository {
            override suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult =
                UpdateCheckResult.UpToDate
            override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> =
                flowOf(UpdateDownloadState.Installing)
            override fun sweepStaleApks() {}
            // sinceVersionCode is ignored — the fake returns whatever was configured.
            override suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo> = releases
        }

    private fun viewModel(
        repo: AppUpdateRepository = fakeRepo(),
        prefs: AppUpdatePreferencesStore = fakePrefs(),
        clock: Clock = MutableClock(),
    ) = StartupUpdateViewModel(
        appUpdateRepository = repo,
        appUpdatePreferencesStore = prefs,
        clock = clock,
        isDevBuild = false,
    )

    @Test
    fun `dialog is null when auto-update is disabled`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = fakePrefs(autoEnabled = false),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is null when no releases are newer`() = runTest {
        val vm = viewModel(repo = fakeRepo(emptyList()))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is null when newer release tags have no APK artifact`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600, downloadUrl = ""))),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog skips a newer unfinished release and offers the latest release with an APK`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(
                listOf(
                    releaseInfo("1.6.0", 10600, downloadUrl = ""),
                    releaseInfo("1.5.1", 10501, downloadUrl = "https://x/1.5.1.apk"),
                ),
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.dialogState.value
        assertNotNull(state)
        assertEquals("1.5.1", state!!.update.versionName)
        assertEquals("https://x/1.5.1.apk", state.update.downloadUrl)
        assertEquals(listOf("1.5.1"), state.releases.map(ReleaseInfo::versionName))
    }

    @Test
    fun `dialog is null when latest version matches ignoredVersionCode`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = fakePrefs(ignored = 10600),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is shown when a newer unignored version is available`() = runTest {
        val releases = listOf(
            releaseInfo("1.6.0", 10600, "Notes 1.6"),
            releaseInfo("1.5.1", 10501, "Notes 1.5.1"),
        )
        val vm = viewModel(repo = fakeRepo(releases))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.dialogState.value
        assertNotNull(state)
        assertEquals(2, state!!.releases.size)
        assertEquals("1.6.0", state.releases[0].versionName)
        assertEquals("https://x/1.6.0.apk", state.update.downloadUrl)
        assertEquals(10600, state.update.versionCode)
    }

    @Test
    fun `ignoreVersion writes pref and clears dialog`() = runTest {
        val prefs = fakePrefs()
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = prefs,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.dialogState.value)

        vm.ignoreVersion(10600)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
        assertEquals(10600, prefs.ignoredVersionCode.first())
    }

    @Test
    fun `checkNow re-shows dialog after dismiss once the throttle interval has elapsed`() = runTest {
        val clock = MutableClock()
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            clock = clock,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.dialogState.value)

        vm.dismissDialog()
        assertNull(vm.dialogState.value)

        vm.checkNow()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.dialogState.value)

        clock.timeMs = StartupUpdateViewModel.AUTO_CHECK_INTERVAL_MS

        vm.checkNow()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.dialogState.value)
    }

    @Test
    fun `checkNow does not launch a duplicate check while dialog is showing`() = runTest {
        var checkCount = 0
        val repo = object : AppUpdateRepository {
            override suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult =
                UpdateCheckResult.UpToDate
            override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> =
                flowOf(UpdateDownloadState.Installing)
            override fun sweepStaleApks() {}
            override suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo> {
                checkCount++
                return listOf(releaseInfo("1.6.0", 10600))
            }
        }
        val vm = viewModel(repo = repo)
        testDispatcher.scheduler.advanceUntilIdle()
        val checksAfterInit = checkCount

        vm.checkNow()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("checkNow() is a no-op while dialog is already showing", checksAfterInit, checkCount)
    }

    @Test
    fun `checkNow does not repeat a recent check when no dialog is showing`() = runTest {
        var checkCount = 0
        val clock = MutableClock()
        val repo = object : AppUpdateRepository {
            override suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult =
                UpdateCheckResult.UpToDate
            override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> =
                flowOf(UpdateDownloadState.Installing)
            override fun sweepStaleApks() {}
            override suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo> {
                checkCount++
                return emptyList()
            }
        }
        val vm = viewModel(repo = repo, clock = clock)
        testDispatcher.scheduler.advanceUntilIdle()

        clock.timeMs = StartupUpdateViewModel.AUTO_CHECK_INTERVAL_MS - 1
        vm.checkNow()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("checks before the two-hour boundary are throttled", 1, checkCount)

        clock.timeMs = StartupUpdateViewModel.AUTO_CHECK_INTERVAL_MS
        vm.checkNow()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("the two-hour boundary permits a new check", 2, checkCount)
    }

    @Test
    fun `dismissDialog clears dialog without writing ignoredVersionCode`() = runTest {
        val prefs = fakePrefs()
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = prefs,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.dialogState.value)

        vm.dismissDialog()

        assertNull(vm.dialogState.value)
        assertEquals(0, prefs.ignoredVersionCode.first())
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
