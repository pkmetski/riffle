package com.riffle.feature.library.ui

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.backhandler.BackHandler
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.Library
import com.riffle.core.models.Source
import com.riffle.feature.navigation.LibraryNav
import com.riffle.feature.navigation.NavigationDrawerViewModel
import com.riffle.feature.navigation.shouldInterceptBackForDrawer
import com.riffle.feature.player.NowPlaying
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

/** Top-level section in the app navigation shell. */
enum class AppSection { Library, Riffle, Downloads, Settings }

/**
 * Callbacks and state that section content composables receive from [AppShellContent].
 *
 * Sections interact with the shell through these rather than reaching directly into the
 * host's state, keeping shell internals encapsulated and section content testable.
 */
class AppShellSectionScope(
    /** Opens the navigation drawer. */
    val onOpenDrawer: () -> Unit,
    /** Navigates back to the section that was active before Settings or Downloads opened. */
    val onNavigateBack: () -> Unit,
    /**
     * Programmatically switches to [section]. Normally sections switch via drawer taps; this
     * allows the startup router (in Library) to switch to Riffle when needed.
     */
    val onSwitchToSection: (AppSection) -> Unit,
    /**
     * Called by Library and Riffle sections when a reader surface becomes active or inactive.
     * While active, drawer swipe gestures and the permanent drawer panel are suppressed.
     */
    val onReaderActiveChanged: (Boolean) -> Unit,
    /** Called by the Library section to update which library is highlighted in the drawer. */
    val onActiveLibraryChanged: (String?) -> Unit,
    /**
     * Pending navigation destination from a lock-screen / notification tap resolved to a
     * [LibraryNav]. Non-null only when this scope belongs to the Library section; null
     * in all other sections so hosts never accidentally navigate Riffle/Settings.
     */
    val pendingLibraryNav: LibraryNav?,
    /** Must be called by the Library section once it has consumed [pendingLibraryNav]. */
    val onPendingLibraryNavHandled: () -> Unit,
    /**
     * Switches to the Library section with [nav] as a pending destination.
     *
     * Call this from Downloads or Riffle when the user taps "Read" or "Listen" on an item —
     * the Library section picks up [nav] as if it arrived from the lock-screen. This avoids
     * each section hosting its own reader routing, keeping readers exclusively in Library.
     */
    val onNavigateToLibraryWith: (LibraryNav) -> Unit,
)

/**
 * Pure stateless shell composable — accepts all drawer state as parameters.
 *
 * Manages:
 * - Section switching (Library / Riffle / Downloads / Settings)
 * - Per-section [androidx.compose.runtime.saveable.SaveableStateHolder] so scroll positions
 *   and transient UI state survive section switches.
 * - [RiffleNavigationDrawer] and its BackHandler.
 * - Routing of [externalPendingLibraryNav] (resolved from NowPlaying or deep-link by the host)
 *   to the Library section scope.
 *
 * Engine surfaces (EPUB, PDF, CBZ readers, audiobook player, local-files picker, share sheet,
 * O'Reilly WebView) are entirely platform-specific and are provided by each host via the
 * composable slots [libraryContent] / [riffleContent] — the shell itself never renders them.
 *
 * Must **not** land in `:shared/commonMain` — see the `checkSharedCommonMainComposables`
 * guardrail. It belongs in `:feature:library-ui` (androidTarget + iOS topology).
 *
 * @param isTabletLayout True when the window satisfies the two-pane / permanent-drawer threshold
 *   (Expanded width + non-Compact height, per ADR 0019).
 * @param allServers Current list of configured sources (excluding Storyteller).
 * @param activeServer The currently active source, or null when none / Riffle is active.
 * @param visibleLibraries Libraries visible in the drawer for the active source.
 * @param activeLibraryId ID of the currently highlighted library in the drawer.
 * @param isRiffleMode Whether the Riffle section was the last active top-level destination.
 * @param showDownloadsLink Whether to show the Downloads link in the drawer.
 * @param serverVersions Cache of source version strings shown in the drawer header.
 * @param externalPendingLibraryNav Pending navigation from the lock-screen / notification shade,
 *   already resolved by the host from a [com.riffle.feature.player.NowPlaying] event.
 * @param onExternalPendingLibraryNavHandled Called when [externalPendingLibraryNav] is consumed.
 * @param onServerSelected Called when the user selects a different source in the drawer.
 * @param onLibrarySelected Called when the user selects a library in the drawer.
 * @param onSetRiffleActive Called when the user taps the Riffle entry in the drawer.
 * @param appVersion Version string shown at the bottom of the drawer; may be null on iOS.
 * @param appSha Git-SHA string shown at the bottom of the drawer; may be null on iOS.
 * @param libraryContent Library section composable. Receives an [AppShellSectionScope].
 * @param riffleContent Riffle section composable. Receives an [AppShellSectionScope].
 * @param downloadsContent Downloads section composable. Receives an [AppShellSectionScope].
 * @param settingsContent Settings section composable. Receives an [AppShellSectionScope].
 * @param updateDialogContent Platform-supplied startup update dialog. No-op on iOS.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AppShellContent(
    isTabletLayout: Boolean,
    allServers: List<Source>,
    activeServer: Source?,
    visibleLibraries: List<Library>,
    activeLibraryId: String?,
    isRiffleMode: Boolean,
    showDownloadsLink: Boolean,
    serverVersions: Map<String, String>,
    externalPendingLibraryNav: LibraryNav?,
    onExternalPendingLibraryNavHandled: () -> Unit,
    onServerSelected: (Source) -> Unit,
    onLibrarySelected: (Library) -> Unit,
    onSetRiffleActive: () -> Unit,
    appVersion: String?,
    appSha: String?,
    libraryContent: @Composable AppShellSectionScope.() -> Unit,
    riffleContent: @Composable AppShellSectionScope.() -> Unit,
    downloadsContent: @Composable AppShellSectionScope.() -> Unit,
    settingsContent: @Composable AppShellSectionScope.() -> Unit,
    updateDialogContent: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var appSection by rememberSaveable { mutableStateOf(AppSection.Library) }
    var previousSection by remember { mutableStateOf(AppSection.Library) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val saveableStateHolder = rememberSaveableStateHolder()
    var isInReaderDestination by remember { mutableStateOf(false) }
    var activeLibraryIdState by remember(activeLibraryId) { mutableStateOf(activeLibraryId) }
    // Navigation requested by Downloads/Riffle sections when the user opens an item for reading.
    // These sections do not host their own reader; instead they route through Library.
    var pendingCrossSectionNav by remember { mutableStateOf<LibraryNav?>(null) }

    // Route externalPendingLibraryNav to the Library section whenever it arrives.
    LaunchedEffect(externalPendingLibraryNav) {
        if (externalPendingLibraryNav != null) {
            appSection = AppSection.Library
        }
    }

    RiffleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !isInReaderDestination,
        usePermanentDrawer = isTabletLayout,
        hidePermanentDrawerPanel = isTabletLayout && isInReaderDestination,
        activeServer = activeServer,
        allServers = allServers,
        visibleLibraries = visibleLibraries,
        activeLibraryId = activeLibraryIdState,
        serverVersions = serverVersions,
        showDownloadsLink = showDownloadsLink,
        isRiffleActive = appSection == AppSection.Riffle || isRiffleMode,
        appVersion = appVersion,
        appSha = appSha,
        onRiffleSelected = {
            scope.launch { drawerState.close() }
            onSetRiffleActive()
            appSection = AppSection.Riffle
        },
        onServerSelected = { source ->
            scope.launch { drawerState.close() }
            appSection = AppSection.Library
            activeLibraryIdState = null
            onServerSelected(source)
        },
        onLibrarySelected = { library ->
            scope.launch { drawerState.close() }
            activeLibraryIdState = library.id
            onLibrarySelected(library)
            appSection = AppSection.Library
        },
        onSettingsSelected = {
            scope.launch { drawerState.close() }
            previousSection = appSection
            appSection = AppSection.Settings
        },
        onDownloadsSelected = {
            scope.launch { drawerState.close() }
            previousSection = appSection
            appSection = AppSection.Downloads
        },
    ) {
        // Each section's composable state (scroll, form input, transient UI) is preserved
        // when switching away and restored on return.
        saveableStateHolder.SaveableStateProvider(appSection.name) {
            val combinedPendingNav = if (appSection == AppSection.Library) {
                pendingCrossSectionNav ?: externalPendingLibraryNav
            } else {
                null
            }
            val sectionScope = AppShellSectionScope(
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onNavigateBack = { appSection = previousSection },
                onSwitchToSection = { section ->
                    if (section != appSection) {
                        previousSection = appSection
                        appSection = section
                    }
                },
                onReaderActiveChanged = { isInReaderDestination = it },
                onActiveLibraryChanged = { activeLibraryIdState = it },
                pendingLibraryNav = combinedPendingNav,
                onPendingLibraryNavHandled = {
                    if (pendingCrossSectionNav != null) {
                        pendingCrossSectionNav = null
                    } else {
                        onExternalPendingLibraryNavHandled()
                    }
                },
                onNavigateToLibraryWith = { nav ->
                    pendingCrossSectionNav = nav
                    previousSection = appSection
                    appSection = AppSection.Library
                },
            )
            when (appSection) {
                AppSection.Library -> with(sectionScope) { libraryContent() }
                AppSection.Riffle -> with(sectionScope) { riffleContent() }
                AppSection.Downloads -> with(sectionScope) { downloadsContent() }
                AppSection.Settings -> with(sectionScope) { settingsContent() }
            }
        }
    }

    // Registered AFTER RiffleNavigationDrawer so it has higher priority in the
    // OnBackPressedDispatcher (last-registered wins on Android). A handler before
    // the NavHost gives the NavController higher priority, causing it to pop library
    // destinations instead of closing the drawer.
    BackHandler(
        enabled = shouldInterceptBackForDrawer(
            usePermanentDrawer = isTabletLayout,
            drawerCurrentOpen = drawerState.currentValue == DrawerValue.Open,
            drawerTargetOpen = drawerState.targetValue == DrawerValue.Open,
        ),
    ) {
        scope.launch { drawerState.close() }
    }

    updateDialogContent()
}

/**
 * Koin-injected shell composable used by the Android and iOS hosts.
 *
 * Collects drawer state from [NavigationDrawerViewModel] and resolves
 * [com.riffle.feature.player.NowPlaying] events to a pending [LibraryNav], then delegates
 * all rendering to [AppShellContent].
 *
 * @param drawerViewModel Koin-injected by default; override in tests.
 * @param libraryObserver Koin-injected by default; used to resolve NowPlaying items to
 *   [LibraryNav] for the lock-screen / notification shade path.
 */
@Composable
fun AppShell(
    isTabletLayout: Boolean,
    appVersion: String?,
    appSha: String?,
    libraryContent: @Composable AppShellSectionScope.() -> Unit,
    riffleContent: @Composable AppShellSectionScope.() -> Unit,
    downloadsContent: @Composable AppShellSectionScope.() -> Unit,
    settingsContent: @Composable AppShellSectionScope.() -> Unit,
    updateDialogContent: @Composable () -> Unit = {},
    drawerViewModel: NavigationDrawerViewModel = koinInject(),
    libraryObserver: LibraryObserver = koinInject(),
) {
    val scope = rememberCoroutineScope()

    val allServers by drawerViewModel.allServers.collectAsState()
    val activeServer by drawerViewModel.activeServer.collectAsState()
    val visibleLibraries by drawerViewModel.visibleLibraries.collectAsState()
    val isRiffleMode by drawerViewModel.isRiffleMode.collectAsState()
    val showDownloadsLink by drawerViewModel.showDownloadsLink.collectAsState()
    val serverVersions by drawerViewModel.serverVersions.collectAsState()

    var activeLibraryId by remember { mutableStateOf<String?>(null) }
    var externalPendingLibraryNav by remember { mutableStateOf<LibraryNav?>(null) }

    // Library-redirect events emitted after a server / library switch.
    LaunchedEffect(drawerViewModel) {
        drawerViewModel.redirectToLibrary.collect { library ->
            drawerViewModel.setActiveLibrary(library.id)
            activeLibraryId = library.id
        }
    }

    // Lock-screen / notification tap → resolve the active player to a LibraryNav and deliver
    // it to the Library section via externalPendingLibraryNav.
    LaunchedEffect(drawerViewModel) {
        drawerViewModel.openNowPlayingRequests.collect {
            val target = drawerViewModel.currentNowPlaying() ?: return@collect
            val item = when (target) {
                is NowPlaying.Audiobook -> libraryObserver.getItem(target.sourceId, target.itemId)
                is NowPlaying.Readaloud -> libraryObserver.getItem(target.itemId)
            } ?: return@collect
            externalPendingLibraryNav = when (target) {
                is NowPlaying.Audiobook -> LibraryNav.AudiobookPlayer(item)
                is NowPlaying.Readaloud -> LibraryNav.Reader(item)
            }
        }
    }

    AppShellContent(
        isTabletLayout = isTabletLayout,
        allServers = allServers,
        activeServer = activeServer,
        visibleLibraries = visibleLibraries,
        activeLibraryId = activeLibraryId,
        isRiffleMode = isRiffleMode,
        showDownloadsLink = showDownloadsLink,
        serverVersions = serverVersions,
        externalPendingLibraryNav = externalPendingLibraryNav,
        onExternalPendingLibraryNavHandled = { externalPendingLibraryNav = null },
        onServerSelected = { source ->
            activeLibraryId = null
            drawerViewModel.setActiveServer(source.id)
            scope.launch {
                // Wait for the new source to be committed before the Library section
                // re-evaluates its start destination.
                withTimeoutOrNull(5_000) {
                    drawerViewModel.activeServer.filterNotNull().first { it.id == source.id }
                }
                externalPendingLibraryNav = null
            }
        },
        onLibrarySelected = { library ->
            activeLibraryId = library.id
            drawerViewModel.setActiveLibrary(library.id)
        },
        onSetRiffleActive = { drawerViewModel.setRiffleActive() },
        appVersion = appVersion,
        appSha = appSha,
        libraryContent = libraryContent,
        riffleContent = riffleContent,
        downloadsContent = downloadsContent,
        settingsContent = settingsContent,
        updateDialogContent = updateDialogContent,
    )
}
