package com.riffle.core.data

import android.content.Context
import com.riffle.core.domain.ApkInstaller
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.DefaultDispatcherProvider
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.network.GitHubRelease
import com.riffle.core.network.GitHubReleaseApi
import com.riffle.core.network.GitHubReleaseResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppUpdateRepositoryImplBetaTest {

    private lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var installer: ApkInstaller

    @Before
    fun setup() {
        cacheDir = Files.createTempDirectory("app-update-beta").toFile()
        context = mockk<Context> { every { this@mockk.cacheDir } returns cacheDir }
        installer = mockk(relaxed = true)
    }

    private fun prefsStore(betaEnabled: Boolean): AppUpdatePreferencesStore =
        object : AppUpdatePreferencesStore {
            override val autoUpdateEnabled: Flow<Boolean> = flowOf(true)
            override val ignoredVersionCode: Flow<Int> = flowOf(0)
            override val betaUpdatesEnabled: Flow<Boolean> = flowOf(betaEnabled)
            override val ignoredVersionWasPreRelease: Flow<Boolean> = flowOf(false)
            override suspend fun setAutoUpdateEnabled(value: Boolean) = Unit
            override suspend fun setIgnoredVersionCode(value: Int) = Unit
            override suspend fun setBetaUpdatesEnabled(value: Boolean) = Unit
            override suspend fun setIgnoredVersionWasPreRelease(value: Boolean) = Unit
        }

    @Test
    fun checkForUpdateExcludesPrereleaseWhenBetaOff() = runTest {
        val releaseApi = mockk<GitHubReleaseApi> {
            coEvery { latestRelease(AppUpdateRepositoryImpl.REPO, false) } returns
                GitHubReleaseResult.NoApplicableRelease
        }
        val repo = AppUpdateRepositoryImpl(context, releaseApi, installer, DefaultDispatcherProvider, prefsStore(false))

        val result = repo.checkForUpdate(10400)

        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun checkForUpdateIncludesPrereleaseWhenBetaOn() = runTest {
        val betaRelease = GitHubRelease(
            tagName = "v1.5.0-beta1",
            apkUrl = "https://x/beta.apk",
            apkSizeBytes = 1L,
            prerelease = true,
        )
        val releaseApi = mockk<GitHubReleaseApi> {
            coEvery { latestRelease(AppUpdateRepositoryImpl.REPO, true) } returns
                GitHubReleaseResult.Success(betaRelease)
        }
        val repo = AppUpdateRepositoryImpl(context, releaseApi, installer, DefaultDispatcherProvider, prefsStore(true))

        val result = repo.checkForUpdate(10400)

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val update = (result as UpdateCheckResult.UpdateAvailable).update
        assertEquals(10500, update.versionCode)
        assertTrue(update.prerelease)
    }

    @Test
    fun listReleasesSinceExcludesPrereleaseWhenBetaOff() = runTest {
        val stableRelease = GitHubRelease(
            tagName = "v1.4.0",
            apkUrl = "https://x/1.4.0.apk",
            apkSizeBytes = 1000L,
            prerelease = false,
        )
        val releaseApi = mockk<GitHubReleaseApi> {
            coEvery { listReleases(AppUpdateRepositoryImpl.REPO, false) } returns listOf(stableRelease)
        }
        val repo = AppUpdateRepositoryImpl(context, releaseApi, installer, DefaultDispatcherProvider, prefsStore(false))

        val results = repo.listReleasesSince(0)

        assertTrue(results.none { it.prerelease })
        assertTrue(results.any { it.versionCode == 10400 })
    }
}
