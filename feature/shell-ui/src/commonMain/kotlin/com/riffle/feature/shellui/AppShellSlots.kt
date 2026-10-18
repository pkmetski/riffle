package com.riffle.feature.shellui

import androidx.compose.runtime.Composable
import com.riffle.feature.navigation.LibraryNav
import com.riffle.feature.player.ui.PlayerHostActions
import com.riffle.feature.reader.ui.ReaderHostActions
import com.riffle.feature.settings.ui.PlatformSettingsHooks
import com.riffle.feature.source.ui.AddSourceBackend

/**
 * Engine surfaces injected by each platform host into [AppShell].
 *
 * Every slot is a platform-specific composable lambda so the shell itself stays in commonMain
 * while each host provides the right engine implementation — Readium fragment vs. Swift/Readium
 * bridge for EPUB, Pdfium vs. PDFKit for PDF, Android Intent vs. UIActivityViewController for
 * share, etc.
 *
 * @param readerHost Renders the reader or player for a [LibraryNav.ReaderDestination].
 *   Receives [ReaderHostActions] (back, switch-to-audiobook, open-in-book) and [PlayerHostActions]
 *   (back, playlist-advance, switch-to-readaloud).
 * @param onboardingHost Source-setup flow: Add Source type picker → credentials → select libraries.
 *   Invoked when the startup router emits [com.riffle.feature.library.HomeViewModel.StartDestination.AddSource]
 *   or the user taps "Add source" from Settings.
 * @param webdavOnboardingHost WebDAV-only credential form reached from Settings → Annotation Sync.
 *   Null if the platform does not implement WebDAV onboarding.
 * @param platformSettingsHooks Platform-specific hooks for the Settings screen (language picker,
 *   volume-key toggle visibility, update-install capability).
 * @param updateDialog Platform-specific update-available dialog, shown by the shell after the
 *   startup check. Null on iOS (iOS uses TestFlight / App Store updates, not in-app prompts).
 * @param languageChangeDialog Platform-specific dialog shown after a language switch that
 *   requires the app to restart. Null on Android (recreate() handles it inline).
 * @param appVersion Version string shown in the navigation drawer and Settings, e.g. "1.4.2".
 * @param appSha Short git SHA shown next to the version in Settings, e.g. "a1b2c3d".
 * @param localFilePicker Invokes the platform's folder picker for adding local files.
 *   Called when the user taps "Add local folder" in Settings. Null on platforms that don't
 *   support local-files import.
 */
data class AppShellSlots(
    val readerHost: @Composable (
        destination: LibraryNav.ReaderDestination,
        readerActions: ReaderHostActions,
        playerActions: PlayerHostActions,
    ) -> Unit,
    val onboardingHost: @Composable (
        onFinished: () -> Unit,
        onCancelled: () -> Unit,
        canNavigateBack: Boolean,
        initialBackend: AddSourceBackend?,
        editId: String?,
    ) -> Unit,
    val webdavOnboardingHost: (@Composable (
        editId: String?,
        onFinished: () -> Unit,
        onCancelled: () -> Unit,
    ) -> Unit)? = null,
    val platformSettingsHooks: PlatformSettingsHooks,
    val updateDialog: (@Composable () -> Unit)? = null,
    val languageChangeDialog: (@Composable () -> Unit)? = null,
    val appVersion: String? = null,
    val appSha: String? = null,
    val localFilePicker: (() -> Unit)? = null,
)
