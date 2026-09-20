package com.riffle.feature.settings

import com.riffle.core.domain.AvailableUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the "App version" row text for all seven [AppUpdateUiState] branches.
 *
 * All seven differed between the platforms, and three of them silently dropped the payload the
 * state carries: `UpdateAvailable` did not name the version, `Downloading` never read `percent`,
 * and `Failed` never named the cause. The three interpolation assertions below are the ones that
 * flip red if that regresses.
 */
class AppUpdateStatusTextTest {

    private val update = AvailableUpdate(
        versionName = "2.6.0",
        versionCode = 260,
        downloadUrl = "https://example.invalid/riffle.apk",
        sizeBytes = 1_234L,
    )

    @Test fun idleShowsTheInstalledVersion() {
        assertEquals("Installed: v2.5.0", AppUpdateStatus.statusText(AppUpdateUiState.Idle, "2.5.0"))
    }

    @Test fun checking() {
        assertEquals("Checking for updates…", AppUpdateStatus.statusText(AppUpdateUiState.Checking, "2.5.0"))
    }

    @Test fun upToDateShowsTheInstalledVersion() {
        assertEquals(
            "Installed: v2.5.0 · Up to date",
            AppUpdateStatus.statusText(AppUpdateUiState.UpToDate, "2.5.0"),
        )
    }

    @Test fun updateAvailableNamesTheOfferedVersion() {
        assertEquals(
            "Update available: v2.6.0",
            AppUpdateStatus.statusText(AppUpdateUiState.UpdateAvailable("2.6.0", update), "2.5.0"),
        )
    }

    @Test fun downloadingShowsThePercentage() {
        assertEquals(
            "Downloading update… 42%",
            AppUpdateStatus.statusText(AppUpdateUiState.Downloading(42), "2.5.0"),
        )
    }

    @Test fun installing() {
        assertEquals("Starting installer…", AppUpdateStatus.statusText(AppUpdateUiState.Installing, "2.5.0"))
    }

    @Test fun failedNamesTheCause() {
        assertEquals(
            "Update check failed: no network",
            AppUpdateStatus.statusText(AppUpdateUiState.Failed("no network"), "2.5.0"),
        )
    }

    @Test fun actionLabelOffersTheUpdateWhenOneIsAvailable() {
        assertEquals("Update", AppUpdateStatus.actionLabel(AppUpdateUiState.UpdateAvailable("2.6.0", update)))
    }

    @Test fun actionLabelOffersARetryAfterAFailure() {
        assertEquals("Retry", AppUpdateStatus.actionLabel(AppUpdateUiState.Failed("boom")))
    }

    @Test fun actionLabelOffersACheckWhenIdleOrUpToDate() {
        assertEquals("Check for updates", AppUpdateStatus.actionLabel(AppUpdateUiState.Idle))
        assertEquals("Check for updates", AppUpdateStatus.actionLabel(AppUpdateUiState.UpToDate))
    }

    @Test fun actionLabelIsAbsentWhileWorkIsInFlight() {
        // A spinner stands in for the button, exactly as Android's trailingContent does.
        assertNull(AppUpdateStatus.actionLabel(AppUpdateUiState.Checking))
        assertNull(AppUpdateStatus.actionLabel(AppUpdateUiState.Downloading(10)))
        assertNull(AppUpdateStatus.actionLabel(AppUpdateUiState.Installing))
    }
}
