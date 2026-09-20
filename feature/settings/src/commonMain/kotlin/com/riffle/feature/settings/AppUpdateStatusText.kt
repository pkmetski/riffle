package com.riffle.feature.settings

/**
 * The untranslated "App version" row text for an [AppUpdateUiState].
 *
 * Every one of the seven branches used to be written twice — `AppVersionSection.kt` on Android and
 * a private `when` in iOS's `SettingsScreen.kt` — and all seven had drifted. Three of them dropped
 * the interpolated payload entirely: iOS rendered "Update available" where Android rendered
 * "Update available: v2.6.0", "Downloading…" where Android rendered "Downloading update… 42%"
 * (`Downloading.percent` was simply never read), and "Check failed" where Android named the cause.
 *
 * Each branch mirrors the `values/strings.xml` entry named in its comment, and
 * `AppUpdateStatusStringsParityTest` in `:app` fails if the two diverge. Android keeps rendering
 * the localized `stringResource` variant because it needs a Compose composition.
 */
object AppUpdateStatus {

    /** The status line under the app name — what the row says about the update state. */
    fun statusText(state: AppUpdateUiState, installedVersionName: String): String = when (state) {
        // R.string.ui_installed_version
        is AppUpdateUiState.Idle -> "Installed: v$installedVersionName"
        // R.string.ui_checking_for_updates
        is AppUpdateUiState.Checking -> "Checking for updates…"
        // R.string.ui_installed_version_up_to_date
        is AppUpdateUiState.UpToDate -> "Installed: v$installedVersionName · Up to date"
        // R.string.ui_update_available_version
        is AppUpdateUiState.UpdateAvailable -> "Update available: v${state.versionName}"
        // R.string.ui_downloading_update_percent
        is AppUpdateUiState.Downloading -> "Downloading update… ${state.percent}%"
        // R.string.ui_starting_installer
        is AppUpdateUiState.Installing -> "Starting installer…"
        // R.string.ui_update_check_failed
        is AppUpdateUiState.Failed -> "Update check failed: ${state.message}"
    }

    /**
     * The trailing action label, or `null` while the row shows a progress indicator instead of a
     * button. Mirrors `AppVersionSection`'s `trailingContent`.
     */
    fun actionLabel(state: AppUpdateUiState): String? = when (state) {
        // Spinner (determinate for Downloading) — no button.
        is AppUpdateUiState.Checking, is AppUpdateUiState.Downloading, is AppUpdateUiState.Installing -> null
        // R.string.ui_update
        is AppUpdateUiState.UpdateAvailable -> "Update"
        // R.string.ui_retry
        is AppUpdateUiState.Failed -> "Retry"
        // R.string.ui_check_for_updates
        else -> "Check for updates"
    }
}
