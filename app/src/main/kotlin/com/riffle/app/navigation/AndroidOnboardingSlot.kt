package com.riffle.app.navigation

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.backhandler.BackHandler
import com.riffle.app.feature.source.localfiles.AddLocalFilesScreen
import com.riffle.app.feature.source.oreilly.OReillyLoginScreen
import com.riffle.core.data.websource.SingletonWebSourceInstaller
import com.riffle.core.domain.PendingSource
import com.riffle.core.models.SourceType
import com.riffle.feature.source.SourceTypePickerViewModel
import com.riffle.feature.source.ui.AddSourceBackend
import com.riffle.feature.source.ui.AddSourceScreen
import com.riffle.feature.source.ui.AddSourceViewModel
import com.riffle.feature.source.ui.SelectLibrariesScreen
import com.riffle.feature.source.ui.SelectLibrariesViewModel
import com.riffle.feature.source.ui.SingletonSourceConfirmScreen
import com.riffle.feature.source.ui.SourceTypePickerScreen
import com.riffle.feature.source.ui.websource.unboundedBrowseSourceTypes
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named

/**
 * Android implementation of the [com.riffle.feature.shellui.AppShell]'s onboardingHost slot.
 *
 * Drives the source-setup flow as a simple state machine — mirroring the iOS
 * [com.riffle.shared.source.SourceOnboardingHost] — so the NavController-based SourceNavGraph can
 * eventually be removed. Both platforms share the same Compose screens from :feature:source-ui;
 * only the navigation mechanism differs.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AndroidOnboardingSlot(
    onFinished: () -> Unit,
    onCancelled: () -> Unit,
    canNavigateBack: Boolean,
    initialBackend: AddSourceBackend?,
    editId: String?,
    windowSizeClass: WindowSizeClass,
) {
    val isExpandedWidth = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    val startStep: AndroidOnboardingStep = remember(initialBackend) {
        val credentialed = initialBackend as? AddSourceBackend.Credentialed
        if (credentialed != null) AndroidOnboardingStep.Credentials(credentialed.sourceType) else AndroidOnboardingStep.Picker
    }
    var step by remember(initialBackend) { mutableStateOf(startStep) }

    val isSubStep = step != AndroidOnboardingStep.Picker
    BackHandler(enabled = isSubStep) { step = AndroidOnboardingStep.Picker }

    when (val current = step) {
        AndroidOnboardingStep.Picker -> {
            val pickerViewModel: SourceTypePickerViewModel = koinViewModel()
            val installedTypes by pickerViewModel.installedTypes.collectAsState()
            val developerModeEnabled by pickerViewModel.developerModeEnabled.collectAsState()
            SourceTypePickerScreen(
                isExpandedWidth = isExpandedWidth,
                showNavigationIcon = canNavigateBack,
                onNavigateBack = onCancelled,
                onPick = { type ->
                    when {
                        type == SourceType.LOCAL_FILES -> step = AndroidOnboardingStep.LocalFiles
                        type == SourceType.OREILLY -> step = AndroidOnboardingStep.OReillyLogin
                        unboundedBrowseSourceTypes().contains(type) ->
                            step = AndroidOnboardingStep.Confirm(type)
                        else -> step = AndroidOnboardingStep.Credentials(type)
                    }
                },
                installedTypes = installedTypes,
                developerModeEnabled = developerModeEnabled,
            )
        }

        is AndroidOnboardingStep.Credentials -> {
            val routeType = AddSourceBackend.Credentialed(
                current.type,
                com.riffle.core.models.ServerType.AUDIOBOOKSHELF,
            ).routeType
            val viewModel: AddSourceViewModel = koinViewModel(
                qualifier = named("paramsAddSource"),
                key = routeType + (editId.orEmpty()),
                parameters = { parametersOf(routeType, editId.orEmpty()) },
            )
            val isEditRouting = initialBackend != null
            AddSourceScreen(
                isExpandedWidth = isExpandedWidth,
                showNavigationIcon = canNavigateBack || isEditRouting,
                onNavigateBack = { step = AndroidOnboardingStep.Picker },
                onAuthenticated = { pending -> step = AndroidOnboardingStep.SelectLibraries(pending) },
                onAutoCompleted = onFinished,
                viewModel = viewModel,
            )
        }

        is AndroidOnboardingStep.Confirm -> {
            val singletonInstaller: SingletonWebSourceInstaller = org.koin.compose.koinInject()
            SingletonSourceConfirmScreen(
                type = current.type,
                isExpandedWidth = isExpandedWidth,
                onNavigateBack = { step = AndroidOnboardingStep.Picker },
                onInstall = {
                    singletonInstaller.install(current.type)
                    onFinished()
                },
            )
        }

        is AndroidOnboardingStep.SelectLibraries -> {
            val viewModel: SelectLibrariesViewModel = koinViewModel()
            SelectLibrariesScreen(
                isExpandedWidth = isExpandedWidth,
                pending = current.pending,
                onNavigateBack = { step = AndroidOnboardingStep.Picker },
                onContinueComplete = onFinished,
                viewModel = viewModel,
            )
        }

        AndroidOnboardingStep.LocalFiles -> AddLocalFilesScreen(
            windowSizeClass = windowSizeClass,
            onDone = onFinished,
            onNavigateBack = { step = AndroidOnboardingStep.Picker },
        )

        AndroidOnboardingStep.OReillyLogin -> OReillyLoginScreen(
            windowSizeClass = windowSizeClass,
            onDone = onFinished,
            onNavigateBack = { step = AndroidOnboardingStep.Picker },
        )
    }
}

/**
 * Android-specific Webdav onboarding slot. Called from Settings for adding or editing a WebDAV
 * annotation-sync connection. Mirrors [com.riffle.shared.source.WebdavOnboardingHost] on iOS.
 */
@Composable
fun AndroidWebdavOnboardingSlot(
    editId: String?,
    onFinished: () -> Unit,
    onCancelled: () -> Unit,
) {
    val routeType = AddSourceBackend.Webdav.routeType
    val viewModel: AddSourceViewModel = koinViewModel(
        qualifier = named("paramsAddSource"),
        key = "webdav_" + (editId.orEmpty()),
        parameters = { parametersOf(routeType, editId.orEmpty()) },
    )
    AddSourceScreen(
        isExpandedWidth = false,
        onNavigateBack = onCancelled,
        onAuthenticated = { onFinished() },
        onAutoCompleted = onFinished,
        viewModel = viewModel,
    )
}

private sealed interface AndroidOnboardingStep {
    data object Picker : AndroidOnboardingStep
    data object OReillyLogin : AndroidOnboardingStep
    data object LocalFiles : AndroidOnboardingStep
    data class Confirm(val type: SourceType) : AndroidOnboardingStep
    data class Credentials(val type: SourceType) : AndroidOnboardingStep
    data class SelectLibraries(val pending: PendingSource) : AndroidOnboardingStep
}
