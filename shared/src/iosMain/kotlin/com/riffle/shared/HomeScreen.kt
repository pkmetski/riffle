package com.riffle.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.feature.shellui.AppShell
import com.riffle.feature.shellui.AppShellSlots
import com.riffle.shared.settings.IosPlatformSettingsHooks
import com.riffle.shared.settings.LanguageChangeRestartDialog
import com.riffle.shared.source.SourceOnboardingHost
import com.riffle.shared.source.WebdavOnboardingHost
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import platform.Foundation.NSBundle

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
    val localFilesLogger = koinInject<Logger>()
    val scope = rememberCoroutineScope()

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
            updateDialog = null,
            languageChangeDialog = {
                LanguageChangeRestartDialog(onDismiss = {})
            },
            appVersion = iosAppVersion,
            appSha = iosAppSha,
            localFilePicker = {
                folderPicker.pickFolder { uri ->
                    if (uri != null) {
                        scope.launch {
                            runCatching { localFilesInstaller.installFolder(uri) }
                                .onFailure { localFilesLogger.e(LogChannel.LocalFiles, it) { "installFolder failed" } }
                        }
                    }
                }
            },
        ),
    )
}
