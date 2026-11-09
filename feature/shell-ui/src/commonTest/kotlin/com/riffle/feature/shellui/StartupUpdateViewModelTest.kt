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
        changelog: String = "",
        downloadUrl: String = "https://x/$versionName.apk",
    ) = ReleaseInfo(
        versionName = versionName,
        versionCode = versionCode,
        changelog = changelog,
        downloadUrl = downloadUrl,
        sizeBytes = 1000L,
    )

    private fun fakePrefs(
        autoEnabled: Boolean = true,
        ignored: Int = 0,
        ignoredWasPreRelease: Boolean = false,
    ): AppUpdatePreferencesStore {
        val autoFlow = MutableStateFlow(autoEnabled)
        val ignoredFlow = MutableStateFlow(ignored)
        val wasPreReleaseFlow = MutableStateFlow(ignoredWasPreRelease)
        return object : AppUpdatePreferencesStore {
            override val autoUpdateEnabled: Flow<Boolean> = autoFlow
            override val ignoredVersionCode: Flow<Int> = ignoredFlow
            override val betaUpdatesEnabled: Flow<Boolean> = MutableStateFlow(false)
            override val ignoredVersionWasPreRelease: Flow<Boolean> = wasPreReleaseFlow
            override suspend fun setAutoUpdateEnabled(value: Boolean) { autoFlow.value = value }
            override suspend fun setIgnoredVersionCode(value: Int) { ignoredFlow.value = value }
            override suspend fun setBetaUpdatesEnabled(value: Boolean) {}
            override suspend fun setIgnoredVersionWasPreRelease(value: Boolean) { wasPreReleaseFlow.value = value }
        }
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
        prefs: AppUpdatePreferencesStore = fakePrefs(),
        clock: Clock = MutableClock(),
    ) = StartupUpdateViewModel(
        appUpdateRepository = repo,
        appUpdatePreferencesStore = prefs,
        clock = clock,
        isDevBuild = false,
        currentVersionCode = 0,
    )

    @Test
    fun `dialog is null when auto-update is disabled`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = fakePrefs(autoEnabled = false),
        )
        advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is null when no releases are newer`() = runTest {
        val vm = viewModel(repo = fakeRepo(emptyList()))
        advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is null when newer release tags have no APK artifact`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600, downloadUrl = ""))),
        )
        advanceUntilIdle()

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
        advanceUntilIdle()

        val state = vm.dialogState.value
        assertNotNull(state)
        assertEquals("1.5.1", state.update.versionName)
        assertEquals("https://x/1.5.1.apk", state.update.downloadUrl)
        assertEquals(listOf("1.5.1"), state.releases.map(ReleaseInfo::versionName))
    }

    @Test
    fun `dialog is null when latest version matches ignoredVersionCode`() = runTest {
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = fakePrefs(ignored = 10600),
        )
        advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is shown when a newer unignored version is available`() = runTest {
        val releases = listOf(
            releaseInfo("1.6.0", 10600, "Notes 1.6"),
            releaseInfo("1.5.1", 10501, "Notes 1.5.1"),
        )
        val vm = viewModel(repo = fakeRepo(releases))
        advanceUntilIdle()

        val state = vm.dialogState.value
        assertNotNull(state)
        assertEquals(2, state.releases.size)
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
        advanceUntilIdle()
        assertNotNull(vm.dialogState.value)

        vm.ignoreVersion(10600, wasPreRelease = false)
        advanceUntilIdle()

        assertNull(vm.dialogState.value)
        assertEquals(10600, prefs.ignoredVersionCode.first())
    }

    @Test
    fun `ignoreVersion writes both prefs when prerelease`() = runTest {
        val prefs = fakePrefs()
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.5.0-beta.1", 10500))),
            prefs = prefs,
        )
        advanceUntilIdle()

        vm.ignoreVersion(10500, wasPreRelease = true)
        advanceUntilIdle()

        assertEquals(10500, prefs.ignoredVersionCode.first())
        assertTrue(prefs.ignoredVersionWasPreRelease.first())
    }

    @Test
    fun `ignoreVersion writes both prefs when stable`() = runTest {
        val prefs = fakePrefs()
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.4.0", 10400))),
            prefs = prefs,
        )
        advanceUntilIdle()

        vm.ignoreVersion(10400, wasPreRelease = false)
        advanceUntilIdle()

        assertEquals(10400, prefs.ignoredVersionCode.first())
        assertFalse(prefs.ignoredVersionWasPreRelease.first())
    }

    @Test
    fun `dismissDialog clears dialog without writing ignoredVersionCode`() = runTest {
        val prefs = fakePrefs()
        val vm = viewModel(
            repo = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            prefs = prefs,
        )
        advanceUntilIdle()
        assertNotNull(vm.dialogState.value)

        vm.dismissDialog()

        assertNull(vm.dialogState.value)
        assertEquals(0, prefs.ignoredVersionCode.first())
    }

    @Test
    fun `prerelease flag on ReleaseInfo is propagated to AvailableUpdate in dialog`() = runTest {
        val prefs = fakePrefs()
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
            prefs = prefs,
        )
        advanceUntilIdle()

        val state = vm.dialogState.value
        assertNotNull(state)
        assertTrue(state.update.prerelease)
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
