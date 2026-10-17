package com.riffle.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.AvailableUpdate
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.settings.AppUpdateUiState
import com.riffle.feature.settings.ui.sections.AppVersionSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the release-page routing added for iOS (#1213).
 *
 * Before the fix, [AppVersionSection] called [onInstallUpdate] (which is a no-op on iOS) when
 * [canInstallUpdate] is false and an update is available. After the fix it calls [onViewReleaseUrl]
 * with [AvailableUpdate.downloadUrl] so [IosPlatformSettingsHooks.onViewReleaseUrl] can open the
 * browser. Reverting the AppVersionSection change makes these tests red.
 */
@OptIn(ExperimentalTestApi::class)
class AppVersionSectionTest {

    private val releaseUrl = "https://github.com/pkmetski/riffle/releases/tag/v2.0"
    private val update = AppUpdateUiState.UpdateAvailable(
        versionName = "2.0",
        update = AvailableUpdate(
            versionName = "2.0",
            versionCode = 200,
            downloadUrl = releaseUrl,
            sizeBytes = 0L,
        ),
    )

    @Test
    fun releasePageButtonCallsOnViewReleaseUrlWhenCannotInstall() = runComposeUiTest {
        val viewedUrls = mutableListOf<String>()
        val installedCount = intArrayOf(0)
        setContent {
            Column {
                AppVersionSection(
                    installedVersionName = "1.0",
                    state = update,
                    autoUpdateEnabled = false,
                    canInstallUpdate = false,
                    onCheckForUpdate = {},
                    onInstallUpdate = { installedCount[0]++ },
                    onViewReleaseUrl = { viewedUrls += it },
                    onSetAutoUpdateEnabled = {},
                    onNavigateToChangelog = {},
                    onVersionTap = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_APP_VERSION_RELEASE_PAGE).performClick()
        assertEquals(
            listOf(releaseUrl), viewedUrls,
            "onViewReleaseUrl must be called with AvailableUpdate.downloadUrl when canInstallUpdate=false"
        )
        assertEquals(
            0, installedCount[0],
            "onInstallUpdate must NOT be called when canInstallUpdate=false"
        )
    }

    @Test
    fun updateButtonCallsOnInstallUpdateWhenCanInstall() = runComposeUiTest {
        val installedCount = intArrayOf(0)
        val viewedUrls = mutableListOf<String>()
        setContent {
            Column {
                AppVersionSection(
                    installedVersionName = "1.0",
                    state = update,
                    autoUpdateEnabled = false,
                    canInstallUpdate = true,
                    onCheckForUpdate = {},
                    onInstallUpdate = { installedCount[0]++ },
                    onViewReleaseUrl = { viewedUrls += it },
                    onSetAutoUpdateEnabled = {},
                    onNavigateToChangelog = {},
                    onVersionTap = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_APP_VERSION_INSTALL_UPDATE).performClick()
        assertTrue(installedCount[0] > 0, "onInstallUpdate must be called when canInstallUpdate=true")
        assertTrue(viewedUrls.isEmpty(), "onViewReleaseUrl must NOT be called when canInstallUpdate=true")
    }
}
