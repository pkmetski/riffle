package com.riffle.shared.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.data.websource.SingletonWebSourceInstaller
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.feature.source.SourceTypePickerViewModel
import com.riffle.feature.source.ui.AddSourceBackend
import com.riffle.feature.source.ui.AddSourceScreen
import com.riffle.feature.source.ui.AddSourceViewModel
import com.riffle.feature.source.ui.SelectLibrariesScreen
import com.riffle.feature.source.ui.SelectLibrariesViewModel
import com.riffle.feature.source.ui.SingletonSourceConfirmScreen
import com.riffle.feature.source.ui.SourceTypePickerScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The iOS host for the source-onboarding flow, rendering the *same* Compose screens the Android
 * app renders (`:feature:source-ui`) rather than a hand-rolled skeleton.
 *
 * Android drives these three destinations through its Navigation graph
 * (`app/.../navigation/SourceNavGraph.kt`); iOS has no navigation library wired up yet, so the
 * flow is a small state machine here. Both platforms share the screens, the ViewModels and the
 * copy — only the navigation mechanism differs.
 *
 * [onFinished] fires when a source has actually been committed, so the caller can refresh its
 * start destination; [onCancelled] fires when the user backs out without adding anything.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun SourceOnboardingHost(
    onFinished: () -> Unit,
    onCancelled: () -> Unit,
) {
    val pickerViewModel = koinInject<SourceTypePickerViewModel>()
    val installedTypes by pickerViewModel.installedTypes.collectAsState()
    val folderPicker = koinInject<FolderPickerInterface>()
    val localFilesInstaller = koinInject<LocalFilesInstallerInterface>()
    val singletonInstaller = koinInject<SingletonWebSourceInstaller>()
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf<OnboardingStep>(OnboardingStep.Picker) }

    when (val current = step) {
        OnboardingStep.Picker -> SourceTypePickerScreen(
            isExpandedWidth = false,
            onNavigateBack = onCancelled,
            onPick = { type ->
                when {
                    type == SourceType.LOCAL_FILES -> folderPicker.pickFolder { uri ->
                        if (uri == null) return@pickFolder
                        scope.launch {
                            runCatching { localFilesInstaller.installFolder(uri) }
                                .onSuccess { onFinished() }
                        }
                    }
                    // Zero-config public catalogues (Chitanka, Gutenberg, radio.es): show a
                    // confirmation screen (mirroring Android's AddChitankaScreen etc.) before
                    // materialising the singleton source row.
                    WebSourceDescriptors.forType(type)?.isSingleton == true ->
                        step = OnboardingStep.Confirm(type)
                    else -> step = OnboardingStep.Credentials(type)
                }
            },
            installedTypes = installedTypes,
            enabledTypes = iosSupportedSourceTypes(),
        )

        is OnboardingStep.Credentials -> {
            // Keyed by SourceType so backing out and picking a different source builds a fresh
            // ViewModel with the right `type` route param (mirrors Android's nav-arg behaviour).
            val viewModel = remember(current.type) { addSourceViewModel(current.type) }
            AddSourceScreen(
                isExpandedWidth = false,
                onNavigateBack = { step = OnboardingStep.Picker },
                onAuthenticated = { pending -> step = OnboardingStep.SelectLibraries(pending) },
                onAutoCompleted = onFinished,
                viewModel = viewModel,
            )
        }

        is OnboardingStep.Confirm -> SingletonSourceConfirmScreen(
            type = current.type,
            isExpandedWidth = false,
            onNavigateBack = { step = OnboardingStep.Picker },
            onInstall = {
                singletonInstaller.install(current.type)
                onFinished()
            },
        )

        is OnboardingStep.SelectLibraries -> {
            val viewModel = remember { selectLibrariesViewModel() }
            SelectLibrariesScreen(
                isExpandedWidth = false,
                pending = current.pending,
                onNavigateBack = { step = OnboardingStep.Picker },
                onContinueComplete = onFinished,
                viewModel = viewModel,
            )
        }
    }
}

private sealed interface OnboardingStep {
    data object Picker : OnboardingStep
    data class Confirm(val type: SourceType) : OnboardingStep
    data class Credentials(val type: SourceType) : OnboardingStep
    data class SelectLibraries(val pending: PendingSource) : OnboardingStep
}

/**
 * Source types iOS can currently install.
 *
 * The bar is **"installing it yields a library the user can actually open"**, not "the install
 * path compiles". Credentialed sources need a Kotlin/Native
 * [com.riffle.core.sources.SourceAdapter] — [SourceType.ABS] and [SourceType.KOMGA] both have
 * commonMain adapters in `core:sources` — *and* a registered `CatalogFactory` plus an iOS surface
 * that consumes it.
 *
 * The unbounded catalogues (Chitanka, Gutenberg, radio.es) and O'Reilly are deliberately absent
 * even though `SingletonWebSourceInstaller` would happily install them:
 *
 *  * Their contents are network-only per ADR 0051, so nothing is mirrored into Room —
 *    `IosLibraryRefresherImpl.refreshLibraryItems` returns early for
 *    `SourceType.isUnboundedCatalog` and reports `Success`. The only browse surface
 *    (`UnboundedBrowseViewModel` / `UnboundedCatalogGrid` and the per-source browse screens)
 *    lives in the Android-only `app` module, and the iOS host renders `LibraryItemsScreen` for
 *    every library with no source-type fork. Installing one therefore produced a permanently
 *    empty library with no error ever surfaced (#1071 §17). Re-admit each type here in the same
 *    change that gives iOS the browse surface (#1072) — not before.
 *  * O'Reilly additionally authenticates through an in-app **WebView login** that harvests the
 *    `orm-jwt` cookie, which iOS has no implementation of; `OReillyCatalogFactory.create` returns
 *    null without one. It reached this set only because it is a credential-less singleton, and
 *    was hidden downstream purely by `SourceTypePickerScreen`'s `developerModeEnabled` default —
 *    a gate `SourceOnboardingHost` never passes a value for. Excluding it here makes that safe by
 *    construction instead of by omission, so wiring the developer-mode flag later cannot install
 *    a cookie-less O'Reilly source.
 */
internal fun iosSupportedSourceTypes(): Set<SourceType> = buildSet {
    add(SourceType.ABS)
    add(SourceType.KOMGA)
    add(SourceType.LOCAL_FILES)
}

/**
 * Builds the Add-Source ViewModel for [type]. The route param handed to Koin is the same
 * `type=` string Android puts in its nav route, so `AddSourceBackend.parseBackend` resolves the
 * identical backend on both platforms. ABS's ServerType discriminator is AUDIOBOOKSHELF here —
 * Storyteller is a Service, configured from Settings, not from the Add-Source picker.
 */
private fun addSourceViewModel(type: SourceType): AddSourceViewModel {
    val routeType = AddSourceBackend.Credentialed(type, ServerType.AUDIOBOOKSHELF).routeType
    return KoinPlatform.getKoin().get { parametersOf(routeType) }
}

private fun selectLibrariesViewModel(): SelectLibrariesViewModel = KoinPlatform.getKoin().get()
