package com.riffle.app.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import com.riffle.app.BuildConfig
import com.riffle.app.feature.source.localfiles.PickFolderContract
import com.riffle.core.data.localfiles.FolderUri
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.feature.library.ui.EditLocalFileMetadataDialog
import com.riffle.feature.source.ui.localfiles.AddLocalFilesCommonViewModel
import com.riffle.feature.source.ui.localfiles.AddLocalFilesScreen
import org.koin.compose.koinInject
import com.riffle.feature.shellui.StartupUpdateViewModel
import com.riffle.app.feature.update.UpdateAvailableSheet
import com.riffle.app.feature.update.UpdateDrawerRow
import com.riffle.app.i18n.AppLocaleController
import com.riffle.app.i18n.findActivity
import com.riffle.feature.library.LibraryItemDetailUiState
import com.riffle.feature.library.LibraryItemDetailViewModel
import com.riffle.feature.navigation.guardedNavigateBack
import com.riffle.feature.settings.SettingsViewModel
import com.riffle.feature.settings.ui.PlatformSettingsHooks
import com.riffle.feature.settings.ui.debug.DebugLogViewModel
import com.riffle.feature.settings.ui.i18n.AppLanguage
import com.riffle.feature.shellui.AppShell
import com.riffle.feature.shellui.AppShellSlots
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

internal const val HOME = "home"

@Composable
fun MainScreen(
    windowSizeClass: WindowSizeClass,
) {
    val startupUpdateVm: StartupUpdateViewModel = koinViewModel()
    val updateState by startupUpdateVm.updateState.collectAsState()
    val updateDownloadState by startupUpdateVm.downloadState.collectAsState()
    val showUpdateDot by startupUpdateVm.showDot.collectAsState()
    val showUpdateSheet by startupUpdateVm.showSheet.collectAsState()

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
    val settingsVm: SettingsViewModel = koinViewModel()
    val debugLogVm: DebugLogViewModel = koinViewModel()

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
                AppLocaleController.setLanguage(context, language)
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
    val localFilesInstaller: LocalFilesInstallerInterface = koinInject()
    val addLocalFilesScope = rememberCoroutineScope()
    val addLocalFilesViewModel = remember { AddLocalFilesCommonViewModel(localFilesInstaller, addLocalFilesScope) }
    val folderLauncher = rememberLauncherForActivityResult(PickFolderContract()) { uri ->
        addLocalFilesViewModel.onFolderPicked(uri?.let { FolderUri(it.toString()) })
    }

    if (showLocalFilePicker) {
        AddLocalFilesScreen(
            onDone = { showLocalFilePicker = false },
            onNavigateBack = { showLocalFilePicker = false },
            onPickFolder = { folderLauncher.launch(Unit) },
            viewModel = addLocalFilesViewModel,
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
                showUpdateDot = showUpdateDot,
                onDrawerOpened = startupUpdateVm::markUpdateSeen,
                updateDrawerRow = updateState?.let { state ->
                    { UpdateDrawerRow(versionName = state.update.versionName, onClick = startupUpdateVm::showUpdateSheet) }
                },
                updateSheet = if (showUpdateSheet) {
                    {
                        updateState?.let { state ->
                            UpdateAvailableSheet(
                                state = state,
                                downloadState = updateDownloadState,
                                onUpdate = startupUpdateVm::startUpdate,
                                onDismiss = startupUpdateVm::dismissSheet,
                            )
                        }
                    }
                } else null,
                appVersion = BuildConfig.VERSION_NAME,
                appSha = BuildConfig.GIT_SHA,
                localFilePicker = { showLocalFilePicker = true },
                onShareAllCrashReports = { subject, chooserTitle ->
                    val files = settingsVm.crashReportFilePaths().map { java.io.File(it) }
                    if (files.isNotEmpty()) {
                        val uris = ArrayList<Uri>(files.size)
                        files.forEach { f ->
                            uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                        }
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "text/plain"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, chooserTitle))
                    }
                },
                onShareSingleCrashReport = { report, subject, chooserTitle ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, report.content)
                    }
                    context.startActivity(Intent.createChooser(intent, chooserTitle))
                },
                onShareDebugLog = { channels, chooserTitle ->
                    val intent = debugLogVm.buildShareIntent(channels)
                    if (intent != null) {
                        context.startActivity(Intent.createChooser(intent, chooserTitle))
                    }
                },
                editLocalFileMetadataDialog = { itemId, sourceId, onDismiss ->
                    AndroidEditMetadataDialogHost(itemId = itemId, sourceId = sourceId, onDismiss = onDismiss)
                },
            ),
        )
    }
}

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

internal fun shouldNavigateHomeOnSourceSwitch(): Boolean = true

@Composable
private fun AndroidEditMetadataDialogHost(
    itemId: String,
    sourceId: String?,
    onDismiss: () -> Unit,
) {
    val vm: LibraryItemDetailViewModel = koinViewModel(parameters = { parametersOf(itemId, sourceId) })
    val uiState by vm.uiState.collectAsState()
    val readyState = uiState as? LibraryItemDetailUiState.Ready

    var pendingCoverCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pendingCoverCallback?.invoke(uri.toString())
        pendingCoverCallback = null
    }

    if (readyState != null) {
        EditLocalFileMetadataDialog(
            item = readyState.item,
            originalItem = readyState.originalItem,
            onPickCover = { onResult ->
                pendingCoverCallback = onResult
                galleryLauncher.launch("image/*")
            },
            onSave = { title, author, seriesName, seriesIndex, coverContentUri, clearCoverOverride ->
                vm.saveMetadataOverride(title, author, seriesName, seriesIndex, coverContentUri, clearCoverOverride)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}
