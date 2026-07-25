package com.riffle.app.feature.update

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupUpdateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun releaseInfo(versionName: String, versionCode: Int, changelog: String = "") = ReleaseInfo(
        versionName = versionName,
        versionCode = versionCode,
        changelog = changelog,
        downloadUrl = "https://x/$versionName.apk",
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

    @Test
    fun `dialog is null when auto-update is disabled`() = runTest {
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            appUpdatePreferencesStore = fakePrefs(autoEnabled = false),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is null when no releases are newer`() = runTest {
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(emptyList()),
            appUpdatePreferencesStore = fakePrefs(),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is null when latest version matches ignoredVersionCode`() = runTest {
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            appUpdatePreferencesStore = fakePrefs(ignored = 10600),
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
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(releases),
            appUpdatePreferencesStore = fakePrefs(),
        )
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
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            appUpdatePreferencesStore = prefs,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.dialogState.value)

        vm.ignoreVersion(10600)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
        assertEquals(10600, prefs.ignoredVersionCode.first())
    }

    @Test
    fun `dismissDialog clears dialog without writing ignoredVersionCode`() = runTest {
        val prefs = fakePrefs()
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            appUpdatePreferencesStore = prefs,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.dialogState.value)

        vm.dismissDialog()

        assertNull(vm.dialogState.value)
        assertEquals(0, prefs.ignoredVersionCode.first())
    }
}
