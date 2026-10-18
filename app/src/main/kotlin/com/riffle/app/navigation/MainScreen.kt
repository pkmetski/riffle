package com.riffle.app.navigation

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.riffle.app.BuildConfig
import com.riffle.app.feature.source.localfiles.AddLocalFilesScreen
import com.riffle.app.feature.update.StartupUpdateViewModel
import com.riffle.app.feature.update.UpdateAvailableDialog
import com.riffle.app.i18n.AppLocaleController
import com.riffle.app.i18n.findActivity
import com.riffle.feature.navigation.guardedNavigateBack
import com.riffle.feature.settings.ui.PlatformSettingsHooks
import com.riffle.feature.settings.ui.i18n.AppLanguage
import com.riffle.feature.shellui.AppShell
import com.riffle.feature.shellui.AppShellSlots
import org.koin.androidx.compose.koinViewModel

internal const val HOME = "home"
internal const val RIFFLE = "riffle"
internal const val SOURCE_SETUP_GRAPH = "source_setup"
internal const val ADD_SOURCE_TYPE_PICKER = "add_source_type_picker"
internal const val ADD_LOCAL_FILES = "add_local_files"
internal const val ADD_CHITANKA = "add_chitanka"
internal const val CHITANKA_BROWSE = "chitanka_browse/{libraryId}/{libraryName}"
internal const val ADD_GUTENBERG = "add_gutenberg"
internal const val GUTENBERG_BROWSE = "gutenberg_browse/{libraryId}/{libraryName}"
internal const val ADD_RADIO_ES = "add_radio_es"
internal const val RADIO_ES_BROWSE = "radio_es_browse/{libraryId}/{libraryName}"
internal const val OREILLY_BROWSE = "oreilly_browse/{libraryId}/{libraryName}"
internal const val ADD_OREILLY = "add_oreilly"
internal const val ADD_SOURCE = "add_source"
internal const val ADD_SOURCE_ROUTE = "add_source?type={type}&editId={editId}"
internal const val SELECT_LIBRARIES = "select_libraries"
internal const val SETTINGS = "settings"
internal const val READALOUD_SETTINGS = "settings/readaloud"
internal const val ANNOTATIONS_SYNC_SETTINGS = "settings/annotation_sync"
internal const val DICTIONARY_PACKS_SETTINGS = "settings/dictionary_packs"
internal const val CHANGELOG = "settings/changelog"
internal const val ANNOTATION_SYNC_MAINTENANCE = "settings/annotation_sync/maintenance"
internal const val DEBUG_LOGS = "settings/debug_logs"
internal const val DEVELOPER_OPTIONS = "settings/developer_options"
internal const val READALOUD_MATCHES = "readaloud_matches/{sourceId}?pairBookId={pairBookId}"
internal const val DOWNLOADS = "downloads"
internal const val LIBRARY_ITEMS = "library_items/{libraryId}/{libraryName}"
internal const val LIBRARY_SECTION = "library_section/{libraryId}/{libraryName}/{sectionType}"
internal const val SERIES_DETAIL = "series_detail/{libraryId}/{seriesId}/{seriesName}"
internal const val COLLECTION_DETAIL = "collection_detail/{libraryId}/{collectionId}/{collectionName}"
internal const val FILTERED_BOOKS = "filtered_books/{libraryId}/{facetType}/{facetValue}"
internal const val LIBRARY_ITEM_DETAIL = "library_item_detail/{itemId}?sourceId={sourceId}"
internal const val PLAYLIST_DETAIL = "playlist_detail/{libraryId}/{playlistId}/{playlistName}"
internal const val ANNOTATION_SEARCH = "annotation_search/{libraryId}?query={query}"
internal const val AUDIOBOOK_PLAYER = "audiobook_player/{sourceId}/{itemId}?startAtSec={startAtSec}&playlistId={playlistId}&libraryId={libraryId}&userPlay={userPlay}"

@Composable
fun MainScreen(
    windowSizeClass: WindowSizeClass,
) {
    val startupUpdateVm: StartupUpdateViewModel = koinViewModel()
    val updateDialogState by startupUpdateVm.dialogState.collectAsState()
    val updateDownloadState by startupUpdateVm.downloadState.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Gate on seenStop so that rotation (which replays ON_START on the new observer
        // with a fresh seenStop=false) doesn't re-show the dialog after the user dismissed
        // it. Only a genuine background→foreground transition sets seenStop=true first.
        var seenStop = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> seenStop = true
                Lifecycle.Event.ON_START -> if (seenStop) startupUpdateVm.checkNow()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val platformHooks = remember(context, activity, lifecycle) {
        object : PlatformSettingsHooks {
            @Composable
            override fun LanguageRow() = Unit

            override fun canInstallUpdate(): Boolean = true

            override fun supportsVolumeKeyNavigation(): Boolean = true

            override fun currentLanguage(): AppLanguage {
                val appLang = AppLocaleController.currentLanguage(context)
                return AppLanguage.fromTag(appLang.tag)
            }

            override fun onLanguageChanged(language: AppLanguage) {
                val appLanguage = com.riffle.app.i18n.AppLanguage.fromTag(language.tag)
                AppLocaleController.setLanguage(context, appLanguage)
                activity?.recreate()
            }

            @Composable
            override fun OnResumeEffect(block: () -> Unit) {
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) block()
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
            }
        }
    }

    // Local-files screen is shown modally on top of the shell when the user taps
    // "Add Local Files" in Settings. It cannot be a plain (() -> Unit) launch because
    // Android's folder picker is integrated into a full Compose screen.
    var showLocalFilePicker by remember { mutableStateOf(false) }

    if (showLocalFilePicker) {
        AddLocalFilesScreen(
            windowSizeClass = windowSizeClass,
            onDone = { showLocalFilePicker = false },
            onNavigateBack = { showLocalFilePicker = false },
        )
    } else {
        AppShell(
            slots = AppShellSlots(
                readerHost = { destination, readerActions, playerActions ->
                    AndroidReaderSlot(
                        destination = destination,
                        readerActions = readerActions,
                        playerActions = playerActions,
                        windowSizeClass = windowSizeClass,
                    )
                },
                onboardingHost = { onFinished, onCancelled, canNavigateBack, initialBackend, editId ->
                    AndroidOnboardingSlot(
                        onFinished = onFinished,
                        onCancelled = onCancelled,
                        canNavigateBack = canNavigateBack,
                        initialBackend = initialBackend,
                        editId = editId,
                        windowSizeClass = windowSizeClass,
                    )
                },
                webdavOnboardingHost = { editId, onFinished, onCancelled ->
                    AndroidWebdavOnboardingSlot(
                        editId = editId,
                        onFinished = onFinished,
                        onCancelled = onCancelled,
                    )
                },
                platformSettingsHooks = platformHooks,
                updateDialog = {
                    updateDialogState?.let { dialogState ->
                        UpdateAvailableDialog(
                            state = dialogState,
                            downloadState = updateDownloadState,
                            onIgnore = startupUpdateVm::ignoreVersion,
                            onUpdate = startupUpdateVm::startUpdate,
                            onDismiss = startupUpdateVm::dismissDialog,
                        )
                    }
                },
                appVersion = BuildConfig.VERSION_NAME,
                appSha = BuildConfig.GIT_SHA,
                localFilePicker = { showLocalFilePicker = true },
            ),
        )
    }
}

// Retained for use by any remaining NavController-based callers (SourceNavGraph, LibraryNavGraph).
internal fun NavController.navigateAsRoot(route: String) {
    navigate(route) {
        popUpTo(HOME) { inclusive = false }
        launchSingleTop = true
    }
}

private fun NavController.isCommittedTop(backStackEntry: NavBackStackEntry): Boolean =
    currentBackStackEntry == backStackEntry

internal fun NavController.popBackStackIfTop(backStackEntry: NavBackStackEntry): Boolean =
    guardedNavigateBack(
        isStillTop = { isCommittedTop(backStackEntry) },
        action = { popBackStack() },
    )

internal fun NavController.navigateAsRootIfTop(backStackEntry: NavBackStackEntry, route: String): Boolean =
    guardedNavigateBack(
        isStillTop = { isCommittedTop(backStackEntry) },
        action = { navigateAsRoot(route) },
    )

internal fun shouldNavigateHomeOnSourceSwitch(): Boolean = true
