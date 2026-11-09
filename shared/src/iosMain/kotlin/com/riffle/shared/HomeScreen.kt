package com.riffle.shared

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.logging.InMemoryLogBuffer
import com.riffle.feature.shellui.AppShell
import com.riffle.feature.shellui.AppShellSlots
import com.riffle.feature.shellui.StartupUpdateViewModel
import com.riffle.shared.generated.resources.Res
import com.riffle.shared.generated.resources.ui_crash_notice_message
import com.riffle.shared.generated.resources.ui_crash_notice_ok
import com.riffle.shared.generated.resources.ui_crash_notice_title
import com.riffle.shared.generated.resources.ui_update_available
import com.riffle.shared.generated.resources.ui_update_available_message
import com.riffle.shared.generated.resources.ui_update_dismiss
import com.riffle.shared.generated.resources.ui_update_view_release
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.source.ui.localfiles.AddLocalFilesCommonViewModel
import com.riffle.feature.source.ui.localfiles.AddLocalFilesScreen
import com.riffle.shared.library.IosEditMetadataDialogHost
import com.riffle.shared.settings.IosPlatformSettingsHooks
import com.riffle.shared.settings.LanguageChangeRestartDialog
import com.riffle.shared.source.SourceOnboardingHost
import com.riffle.shared.source.WebdavOnboardingHost
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

@Composable
fun HomeScreen() {
    val iosAppVersion = remember {
        NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String
    }
    val iosAppSha = remember {
        NSBundle.mainBundle.infoDictionary?.get("CFBundleGitSHA") as? String
    }

    val folderPicker = koinInject<FolderPickerInterface>()
    val localFilesInstaller = koinInject<LocalFilesInstallerInterface>()
    val shareSheetPresenter = koinInject<IosShareSheetPresenter>()
    val crashReportRepository = koinInject<CrashReportRepository>()
    val logBuffer = koinInject<InMemoryLogBuffer>()
    val startupUpdateVm = koinViewModel<StartupUpdateViewModel>()
    val updateDialogState by startupUpdateVm.dialogState.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddLocalFiles by remember { mutableStateOf(false) }
    var showCrashNotice by remember {
        mutableStateOf(crashReportRepository.listCrashReports().isNotEmpty())
    }

    if (showAddLocalFiles) {
        val viewModel = remember { AddLocalFilesCommonViewModel(localFilesInstaller, scope) }
        AddLocalFilesScreen(
            onDone = { showAddLocalFiles = false },
            onNavigateBack = { showAddLocalFiles = false },
            onPickFolder = { folderPicker.pickFolder { uri -> viewModel.onFolderPicked(uri) } },
            viewModel = viewModel,
        )
        return
    }

    AppShell(
        slots = AppShellSlots(
            readerHost = { destination, readerActions, playerActions ->
                ReaderHost(
                    destination = destination,
                    readerActions = readerActions,
                    playerActions = playerActions,
                )
            },
            onboardingHost = { onFinished, onCancelled, canNavigateBack, initialBackend, editId ->
                SourceOnboardingHost(
                    onFinished = onFinished,
                    onCancelled = onCancelled,
                    canNavigateBack = canNavigateBack,
                    initialBackend = initialBackend,
                    editId = editId,
                )
            },
            webdavOnboardingHost = { editId, onFinished, onCancelled ->
                WebdavOnboardingHost(
                    editId = editId,
                    onFinished = onFinished,
                    onCancelled = onCancelled,
                )
            },
            platformSettingsHooks = IosPlatformSettingsHooks,
            updateDialog = {
                updateDialogState?.let { dialogState ->
                    AlertDialog(
                        onDismissRequest = startupUpdateVm::dismissDialog,
                        title = { Text(stringResource(Res.string.ui_update_available)) },
                        text = { Text(stringResource(Res.string.ui_update_available_message, dialogState.update.versionName)) },
                        confirmButton = {
                            TextButton(onClick = {
                                NSURL.URLWithString(dialogState.update.downloadUrl)?.let {
                                    UIApplication.sharedApplication.openURL(it)
                                }
                                startupUpdateVm.dismissDialog()
                            }) { Text(stringResource(Res.string.ui_update_view_release)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                startupUpdateVm.ignoreVersion(
                                    dialogState.update.versionCode,
                                    dialogState.update.prerelease,
                                )
                            }) { Text(stringResource(Res.string.ui_update_dismiss)) }
                        },
                    )
                }
            },
            languageChangeDialog = {
                LanguageChangeRestartDialog(onDismiss = {})
            },
            appVersion = iosAppVersion,
            appSha = iosAppSha,
            localFilePicker = { showAddLocalFiles = true },
            editLocalFileMetadataDialog = { itemId, sourceId, onDismiss ->
                IosEditMetadataDialogHost(itemId = itemId, sourceId = sourceId, onDismiss = onDismiss)
            },
            onShareAllCrashReports = { subject, _ ->
                val reports = crashReportRepository.listCrashReports()
                val paths = crashReportRepository.resolveReportFilePaths(reports.map { it.id })
                if (paths.isNotEmpty()) {
                    shareSheetPresenter.shareFiles(filePaths = paths, subject = subject)
                }
            },
            onShareSingleCrashReport = { report, subject, _ ->
                shareSheetPresenter.shareText(text = report.content, subject = subject)
            },
            onShareDebugLog = { channels, _ ->
                val text = InMemoryLogBuffer.formatEntries(logBuffer.snapshot(), channels)
                if (text.isNotEmpty()) {
                    shareSheetPresenter.shareText(text = text, subject = null)
                }
            },
            crashNoticeDialog = {
                if (showCrashNotice) {
                    AlertDialog(
                        onDismissRequest = { showCrashNotice = false },
                        title = { Text(stringResource(Res.string.ui_crash_notice_title)) },
                        text = { Text(stringResource(Res.string.ui_crash_notice_message)) },
                        confirmButton = {
                            TextButton(onClick = { showCrashNotice = false }) { Text(stringResource(Res.string.ui_crash_notice_ok)) }
                        },
                    )
                }
            },
        ),
    )
}
