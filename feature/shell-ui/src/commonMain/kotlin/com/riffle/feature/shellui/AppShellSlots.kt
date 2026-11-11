package com.riffle.feature.shellui

import androidx.compose.runtime.Composable
import com.riffle.core.logging.LogChannel
import com.riffle.core.models.CrashReport
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
 * @param showUpdateDot True when a new version is available and not yet seen by the user. Renders
 *   as a small dot badge on the ≡ hamburger icon in [RiffleScreen].
 * @param onDrawerOpened Invoked each time the navigation drawer opens. Used to mark the current
 *   update version as seen (clearing the dot).
 * @param updateDrawerRow Optional composable rendered inside the navigation drawer footer, above
 *   Downloads and Settings, when an update is available.
 * @param updateSheet Optional composable rendered at top level (outside the drawer) when the user
 *   has tapped the update drawer row. Platform-specific: Android shows a bottom sheet with
 *   download/install; iOS shows an AlertDialog with a "View release" link to GitHub Releases.
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
    val webdavOnboardingHost: (
        @Composable (
            editId: String?,
            onFinished: () -> Unit,
            onCancelled: () -> Unit,
        ) -> Unit
    )? = null,
    val platformSettingsHooks: PlatformSettingsHooks,
    val showUpdateDot: Boolean = false,
    val onDrawerOpened: (() -> Unit)? = null,
    val updateDrawerRow: (@Composable () -> Unit)? = null,
    val updateSheet: (@Composable () -> Unit)? = null,
    val languageChangeDialog: (@Composable () -> Unit)? = null,
    val appVersion: String? = null,
    val appSha: String? = null,
    val localFilePicker: (() -> Unit)? = null,
    val onShareAllCrashReports: ((subject: String, chooserTitle: String) -> Unit)? = null,
    val onShareSingleCrashReport: ((report: CrashReport, subject: String, chooserTitle: String) -> Unit)? = null,
    val onShareDebugLog: ((channels: Set<LogChannel>?, chooserTitle: String) -> Unit)? = null,
    val editLocalFileMetadataDialog: (@Composable (itemId: String, sourceId: String?, onDismiss: () -> Unit) -> Unit)? = null,
    val crashNoticeDialog: (@Composable () -> Unit)? = null,
)
