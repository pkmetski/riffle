package com.riffle.shared.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.backhandler.BackHandler
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.data.websource.SingletonWebSourceInstaller
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.feature.designsystem.isExpandedWidth
import com.riffle.feature.source.SourceTypePickerViewModel
import com.riffle.feature.source.ui.AddSourceBackend
import com.riffle.feature.source.ui.AddSourceScreen
import com.riffle.feature.source.ui.AddSourceViewModel
import com.riffle.feature.source.ui.SelectLibrariesScreen
import com.riffle.feature.source.ui.SelectLibrariesViewModel
import com.riffle.feature.source.ui.SingletonSourceConfirmScreen
import com.riffle.feature.source.ui.SourceTypePickerScreen
import com.riffle.feature.source.ui.websource.unboundedBrowseSourceTypes
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
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
fun SourceOnboardingHost(
    onFinished: () -> Unit,
    onCancelled: () -> Unit,
    canNavigateBack: Boolean = true,
    initialBackend: AddSourceBackend? = null,
    editId: String? = null,
) {
    val pickerViewModel = koinInject<SourceTypePickerViewModel>()
    val installedTypes by pickerViewModel.installedTypes.collectAsState()
    val developerModeEnabled by pickerViewModel.developerModeEnabled.collectAsState()
    val folderPicker = koinInject<FolderPickerInterface>()
    val localFilesInstaller = koinInject<LocalFilesInstallerInterface>()
    val singletonInstaller = koinInject<SingletonWebSourceInstaller>()
    val scope = rememberCoroutineScope()

    // When an initialBackend is provided (edit-source routing), skip the picker and go directly
    // to the credentials step. The cast to Credentialed is safe: edit-source never routes through
    // Webdav (which has its own WebdavOnboardingHost) and only ABS/Komga reach this path.
    val startStep: OnboardingStep = remember(initialBackend) {
        val credentialed = initialBackend as? AddSourceBackend.Credentialed
        if (credentialed != null) OnboardingStep.Credentials(credentialed.sourceType) else OnboardingStep.Picker
    }
    var step by remember(initialBackend) { mutableStateOf(startStep) }

    // Intercept the system / swipe-back gesture when the user is past the picker step, so that
    // backing out within the flow returns to the picker rather than dismissing the host view
    // controller. At the picker level with canNavigateBack=false (first-run onboarding) there is
    // intentionally no BackHandler — the user must complete the flow.
    val isSubStep = step != OnboardingStep.Picker
    BackHandler(enabled = isSubStep) { step = OnboardingStep.Picker }

    // iOS used to hardcode `false` at all four of these call sites, so an iPad — in landscape, in
    // Split View, or a 12.9" in portrait — rendered the phone layout and stretched the credential
    // form edge to edge. Android reads the same predicate from its `WindowSizeClass`
    // (`app/.../navigation/SourceNavGraph.kt`); both index on the 840dp Expanded breakpoint.
    val expandedWidth = isExpandedWidth()

    when (val current = step) {
        OnboardingStep.Picker -> SourceTypePickerScreen(
            isExpandedWidth = expandedWidth,
            showNavigationIcon = canNavigateBack,
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
                    type == SourceType.OREILLY -> step = OnboardingStep.OReillyLogin
                    // Zero-config public catalogues (Chitanka, Gutenberg, radio.es): show a
                    // confirmation screen (mirroring Android's AddChitankaScreen etc.) before
                    // materialising the singleton source row.
                    WebSourceDescriptors.forType(type)?.isSingleton == true ->
                        step = OnboardingStep.Confirm(type)
                    else -> step = OnboardingStep.Credentials(type)
                }
            },
            installedTypes = installedTypes,
            developerModeEnabled = developerModeEnabled,
            enabledTypes = iosSupportedSourceTypes(),
        )

        is OnboardingStep.Credentials -> {
            // Keyed by SourceType so backing out and picking a different source builds a fresh
            // ViewModel with the right `type` route param (mirrors Android's nav-arg behaviour).
            // Show the nav icon only when the picker itself is navigable (canNavigateBack=true or
            // we arrived here via edit-source routing). During first-run onboarding the picker has
            // no back and neither does AddSource — the user must complete or use the system back.
            val isEditRouting = initialBackend != null
            val viewModel = remember(current.type) { addSourceViewModel(current.type, editId) }
            AddSourceScreen(
                isExpandedWidth = expandedWidth,
                showNavigationIcon = canNavigateBack || isEditRouting,
                onNavigateBack = { step = OnboardingStep.Picker },
                onAuthenticated = { pending -> step = OnboardingStep.SelectLibraries(pending) },
                onAutoCompleted = onFinished,
                viewModel = viewModel,
            )
        }

        is OnboardingStep.Confirm -> SingletonSourceConfirmScreen(
            type = current.type,
            isExpandedWidth = expandedWidth,
            onNavigateBack = { step = OnboardingStep.Picker },
            onInstall = {
                singletonInstaller.install(current.type)
                onFinished()
            },
        )

        is OnboardingStep.SelectLibraries -> {
            val viewModel = remember { selectLibrariesViewModel() }
            SelectLibrariesScreen(
                isExpandedWidth = expandedWidth,
                pending = current.pending,
                onNavigateBack = { step = OnboardingStep.Picker },
                onContinueComplete = onFinished,
                viewModel = viewModel,
            )
        }

        OnboardingStep.OReillyLogin -> IosOReillyLoginScreen(
            onDone = onFinished,
            onNavigateBack = { step = OnboardingStep.Picker },
        )
    }
}

private sealed interface OnboardingStep {
    data object Picker : OnboardingStep
    data object OReillyLogin : OnboardingStep
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
 * The unbounded catalogues (Chitanka, Gutenberg, radio.es) are here because iOS now has the
 * surface they need: `CatalogFactory` entries in `Koin.kt`'s `catalogFactoriesBySourceType`, and
 * [com.riffle.feature.source.ui.websource.UnboundedBrowseScreen], which `HomeScreen`'s
 * `LibraryHost` renders instead of `LibraryItemsScreen` for any `SourceType.isUnboundedCatalog`
 * library. They were briefly excluded while that surface did not exist and installing one produced
 * a permanently empty library with no error (#1071 §17); the set is derived from
 * [com.riffle.feature.source.ui.websource.unboundedBrowseSourceTypes] rather than re-listed so
 * the two cannot
 * drift — offering a type the browse screen has no ViewModel for would crash it.
 *
 * O'Reilly is gated behind `SourceTypePickerScreen`'s `developerModeEnabled` flag (see
 * [OReillyWebSourceDescriptor.requiresDeveloperMode]) so it is only visible when developer
 * mode is unlocked. It is included in this set so the card is enabled (not greyed out) once
 * it appears. The [OnboardingStep.OReillyLogin] step implements the iOS WebView login that
 * harvests the `orm-jwt` cookie.
 */
internal fun iosSupportedSourceTypes(): Set<SourceType> = buildSet {
    add(SourceType.ABS)
    add(SourceType.KOMGA)
    add(SourceType.LOCAL_FILES)
    add(SourceType.OREILLY)
    addAll(unboundedBrowseSourceTypes())
}

/**
 * Builds the Add-Source ViewModel for [type]. The route param handed to Koin is the same
 * `type=` string Android puts in its nav route, so `AddSourceBackend.parseBackend` resolves the
 * identical backend on both platforms. ABS's ServerType discriminator is AUDIOBOOKSHELF here —
 * Storyteller is a Service, configured from Settings, not from the Add-Source picker.
 */
private fun addSourceViewModel(type: SourceType, editId: String? = null): AddSourceViewModel {
    val routeType = AddSourceBackend.Credentialed(type, ServerType.AUDIOBOOKSHELF).routeType
    return KoinPlatform.getKoin().get { parametersOf(routeType, editId.orEmpty()) }
}

/**
 * The WebDAV annotation-sync connect form, reached from the Settings "Annotations Sync" row —
 * the same entry point Android's `AnnotationsSyncSettingsScreen` uses.
 *
 * WebDAV is not a browsable Source and so is deliberately absent from [iosSupportedSourceTypes];
 * it is a sidecar the user attaches to sync highlights and reading positions between devices.
 * The form itself is the shared [AddSourceScreen] with [AddSourceBackend.Webdav] — this host
 * exists only to hand the ViewModel that route param, because [SourceOnboardingHost] always
 * builds a `Credentialed` backend and therefore could never construct this one.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun WebdavOnboardingHost(
    onFinished: () -> Unit,
    onCancelled: () -> Unit,
) {
    val viewModel = remember { webdavAddSourceViewModel() }
    AddSourceScreen(
        isExpandedWidth = false,
        onNavigateBack = onCancelled,
        // WebDAV has no libraries to select and no PendingSource to commit: connecting is the
        // whole flow, so both terminal callbacks finish.
        onAuthenticated = { onFinished() },
        onAutoCompleted = onFinished,
        viewModel = viewModel,
    )
}

private fun webdavAddSourceViewModel(): AddSourceViewModel =
    KoinPlatform.getKoin().get { parametersOf(AddSourceBackend.Webdav.routeType) }

private fun selectLibrariesViewModel(): SelectLibrariesViewModel = KoinPlatform.getKoin().get()
