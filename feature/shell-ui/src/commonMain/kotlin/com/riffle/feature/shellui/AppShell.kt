package com.riffle.feature.shellui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import com.riffle.core.data.annotationLocatorJson
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SourceType
import com.riffle.feature.designsystem.BookCoverTile
import com.riffle.feature.designsystem.NoOp
import com.riffle.feature.designsystem.coverGridMinCell
import com.riffle.feature.designsystem.isExpandedWidth
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.library.AnnotationSearchViewModel
import com.riffle.feature.library.AnnotationsListViewModel
import com.riffle.feature.library.FilteredBooksViewModel
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.LibraryItemsViewModel
import com.riffle.feature.library.PlaylistDetailViewModel
import com.riffle.feature.library.RiffleViewModel
import com.riffle.feature.library.navigateFromHome
import com.riffle.feature.library.ui.AnnotationSearchResultsScreen
import com.riffle.feature.library.ui.CollectionDetailScreen
import com.riffle.feature.library.ui.DownloadsActions
import com.riffle.feature.library.ui.DownloadsScreen
import com.riffle.feature.library.ui.FilteredBooksScreen
import com.riffle.feature.library.ui.LibraryItemDetailActions
import com.riffle.feature.library.ui.LibraryItemDetailScreen
import com.riffle.feature.library.ui.LibraryItemsActions
import com.riffle.feature.library.ui.LibraryItemsScreen
import com.riffle.feature.library.ui.LibrarySectionScreen
import com.riffle.feature.library.ui.PlaylistDetailScreen
import com.riffle.feature.library.ui.PlaylistItemRow
import com.riffle.feature.library.ui.RiffleNavigationDrawer
import com.riffle.feature.library.ui.RiffleScreen
import com.riffle.feature.library.ui.ScreenScopedViewModelHost
import com.riffle.feature.library.ui.SeriesDetailScreen
import com.riffle.feature.library.ui.annotationSearchLabels
import com.riffle.feature.library.ui.filteredBooksLabels
import com.riffle.feature.library.ui.generated.resources.Res
import com.riffle.feature.library.ui.generated.resources.ui_book_not_found
import com.riffle.feature.library.ui.generated.resources.ui_retry
import com.riffle.feature.library.ui.generated.resources.ui_unable_to_connect_to_source
import com.riffle.feature.library.ui.playlistLabels
import com.riffle.feature.library.ui.websource.UnboundedBrowseScreen
import com.riffle.feature.navigation.LibraryNav
import com.riffle.feature.navigation.LibraryNavStateHolder
import com.riffle.feature.navigation.NavigationDrawerViewModel
import com.riffle.feature.navigation.NavigationIntent
import com.riffle.feature.navigation.isAlreadyAtPlayerDestination
import com.riffle.feature.navigation.openItemForReading
import com.riffle.feature.navigation.openReadItemForReading
import com.riffle.feature.navigation.playlistAdvanceNav
import com.riffle.feature.navigation.playlistPlayerNav
import com.riffle.feature.navigation.reduceNavStack
import com.riffle.feature.player.AudiobookPlayIntentRegistry
import com.riffle.feature.player.NowPlaying
import com.riffle.feature.player.ui.PlayerHostActions
import com.riffle.feature.reader.highlights.ReaderSource
import com.riffle.feature.reader.ui.ReaderHostActions
import com.riffle.feature.settings.SettingsViewModel
import com.riffle.feature.settings.ui.SettingsActions
import com.riffle.feature.settings.ui.SettingsScreen
import com.riffle.feature.settings.ui.annotationsync.AnnotationSyncMaintenanceScreen
import com.riffle.feature.settings.ui.annotationsync.AnnotationSyncMaintenanceViewModel
import com.riffle.feature.settings.ui.annotationsync.AnnotationsSyncSettingsScreen
import com.riffle.feature.settings.ui.changelog.ChangelogScreen
import com.riffle.feature.settings.ui.changelog.ChangelogViewModel
import com.riffle.feature.settings.ui.debug.DebugLogScreen
import com.riffle.feature.settings.ui.developer.DeveloperOptionsScreen
import com.riffle.feature.settings.ui.dictionary.DictionaryPacksScreen
import com.riffle.feature.settings.ui.readaloud.ReadaloudMatchesScreen
import com.riffle.feature.settings.ui.readaloud.ReadaloudMatchesViewModel
import com.riffle.feature.settings.ui.readaloud.ReadaloudSettingsScreen
import com.riffle.feature.source.ui.AddSourceBackend
import com.riffle.feature.source.ui.websource.shouldRenderUnboundedBrowse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The shared application shell rendered by both the Android app and the iOS app.
 *
 * Manages sections (Library / Riffle / Downloads / Settings), per-section back stacks via
 * [rememberSaveableStateHolder], multiplatform [BackHandler], the shared [RiffleNavigationDrawer],
 * startup routing, now-playing navigator collector with dedupe, and the startup update dialog.
 *
 * Platform-specific engine surfaces — EPUB reader, PDF reader, CBZ decoder, audiobook player,
 * source onboarding, local-file picker, share sheet — are provided by each host via [slots].
 *
 * This composable must NOT be placed in `:shared/commonMain` — the
 * `checkSharedCommonMainComposables` guardrail enforces this. It lives in `feature:shell-ui`
 * which has an androidTarget + iOS topology so both hosts can render it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AppShell(
    slots: AppShellSlots,
    viewModel: NavigationDrawerViewModel = koinInject(),
    homeViewModel: HomeViewModel = koinInject(),
    navStateHolder: LibraryNavStateHolder = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var appSection by rememberSaveable { mutableStateOf(AppSection.Library) }
    var settingsSubScreen by remember { mutableStateOf<SettingsSubScreen?>(null) }
    var destination by remember { mutableStateOf<HomeViewModel.StartDestination?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var activeLibraryId by remember { mutableStateOf<String?>(null) }
    // Hoisted here (not inside LibraryHost) so the stack survives the ModalNavigationDrawer ↔
    // PermanentNavigationDrawer switch that RiffleNavigationDrawer performs on rotation. When the
    // drawer type changes, the content lambda is remounted, losing any remember state inside it.
    // Keeping navStack in AppShell (which is never remounted during a drawer switch) preserves it.
    // navStateHolder also preserves it across Activity recreations (e.g. Samsung foldable
    // cover↔main screen switches, which move the window between displays and recreate the Activity
    // even when configChanges lists density/screenSize/screenLayout).
    var libraryNavStack by remember(activeLibraryId) {
        mutableStateOf(
            if (activeLibraryId != null && navStateHolder.libraryId == activeLibraryId) {
                navStateHolder.stack
            } else {
                listOf(LibraryNav.Items)
            },
        )
    }
    var isInReaderDestination by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var pendingLibraryNav by remember { mutableStateOf<LibraryNav?>(null) }
    var previousSection by remember { mutableStateOf(AppSection.Library) }
    var pendingRiffleNav by remember { mutableStateOf<LibraryNav?>(null) }
    val libraryObserver = koinInject<LibraryObserver>()

    val isTabletLayout = isExpandedWidth()

    val allServers by viewModel.allServers.collectAsState()
    val activeServer by viewModel.activeServer.collectAsState()
    val visibleLibraries by viewModel.visibleLibraries.collectAsState()
    val isRiffleMode by viewModel.isRiffleMode.collectAsState()
    val showDownloadsLink by viewModel.showDownloadsLink.collectAsState()
    val serverVersions by viewModel.serverVersions.collectAsState()

    LaunchedEffect(refreshKey) {
        navigateFromHome(
            awaitResumed = { /* no lifecycle gate needed — Compose's LaunchedEffect already tracks composition */ },
            viewModel = homeViewModel,
        ) { dest ->
            destination = dest
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.openNowPlayingRequests.collect {
            val target = viewModel.currentNowPlaying() ?: return@collect
            val item = when (target) {
                is NowPlaying.Audiobook -> libraryObserver.getItem(target.sourceId, target.itemId)
                is NowPlaying.Readaloud -> libraryObserver.getItem(target.itemId)
            } ?: return@collect
            val nav = when (target) {
                is NowPlaying.Audiobook -> LibraryNav.AudiobookPlayer(item)
                is NowPlaying.Readaloud -> LibraryNav.Reader(item)
            }
            pendingLibraryNav = nav
            appSection = AppSection.Library
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.redirectToLibrary.collect { library ->
            val srcType = activeServer?.type ?: return@collect
            destination = HomeViewModel.StartDestination.Library(
                sourceType = srcType,
                libraryId = library.id,
                libraryName = library.name,
            )
            activeLibraryId = library.id
            viewModel.setActiveLibrary(library.id)
        }
    }

    slots.languageChangeDialog?.invoke()
    slots.updateDialog?.invoke()

    val drawerGesturesEnabled = !isInReaderDestination

    RiffleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerGesturesEnabled,
        usePermanentDrawer = isTabletLayout,
        hidePermanentDrawerPanel = isTabletLayout && isInReaderDestination,
        activeServer = activeServer,
        allServers = allServers,
        visibleLibraries = visibleLibraries,
        activeLibraryId = activeLibraryId,
        serverVersions = serverVersions,
        showDownloadsLink = showDownloadsLink,
        isRiffleActive = appSection == AppSection.Riffle || isRiffleMode,
        appVersion = slots.appVersion,
        appSha = slots.appSha,
        onRiffleSelected = {
            scope.launch { drawerState.close() }
            viewModel.setRiffleActive()
            appSection = AppSection.Riffle
        },
        onServerSelected = { source ->
            scope.launch { drawerState.close() }
            appSection = AppSection.Library
            activeLibraryId = null
            destination = null
            viewModel.setActiveServer(source.id)
            scope.launch {
                withTimeoutOrNull(5_000) {
                    viewModel.activeServer.first { it?.id == source.id }
                }
                refreshKey++
            }
        },
        onLibrarySelected = { library ->
            scope.launch { drawerState.close() }
            val srcType = activeServer?.type
            if (srcType != null) {
                activeLibraryId = library.id
                viewModel.setActiveLibrary(library.id)
                destination = HomeViewModel.StartDestination.Library(
                    sourceType = srcType,
                    libraryId = library.id,
                    libraryName = library.name,
                )
            }
        },
        onSettingsSelected = {
            scope.launch { drawerState.close() }
            previousSection = appSection
            settingsSubScreen = null
            appSection = AppSection.Settings
        },
        onDownloadsSelected = {
            scope.launch { drawerState.close() }
            previousSection = appSection
            appSection = AppSection.Downloads
        },
    ) {
        when (appSection) {
            AppSection.Settings -> {
                BackHandler(enabled = settingsSubScreen != null) { settingsSubScreen = null }
                BackHandler(enabled = settingsSubScreen == null) { appSection = previousSection }
                when (val sub = settingsSubScreen) {
                    is SettingsSubScreen.WebdavAddSource -> {
                        val webdav = slots.webdavOnboardingHost
                        if (webdav != null) {
                            webdav(
                                sub.editId,
                                { settingsSubScreen = null },
                                { settingsSubScreen = null },
                            )
                        } else {
                            slots.onboardingHost(
                                { settingsSubScreen = null },
                                { settingsSubScreen = null },
                                true,
                                AddSourceBackend.Webdav,
                                sub.editId,
                            )
                        }
                    }
                    is SettingsSubScreen.AddSource -> slots.onboardingHost(
                        { settingsSubScreen = null },
                        { settingsSubScreen = null },
                        true,
                        sub.backend,
                        sub.editId,
                    )
                    SettingsSubScreen.AnnotationsSync -> AnnotationsSyncSettingsScreen(
                        onNavigateBack = { settingsSubScreen = null },
                        onNavigateToAddSource = { backend, editId ->
                            settingsSubScreen = when (backend) {
                                is AddSourceBackend.Webdav -> SettingsSubScreen.WebdavAddSource(editId)
                                else -> SettingsSubScreen.AddSource(backend, editId)
                            }
                        },
                        onNavigateToMaintenance = { settingsSubScreen = SettingsSubScreen.AnnotationSyncMaintenance },
                    )
                    SettingsSubScreen.AnnotationSyncMaintenance -> {
                        val maintenanceViewModel = koinInject<AnnotationSyncMaintenanceViewModel>()
                        AnnotationSyncMaintenanceScreen(
                            onNavigateBack = { settingsSubScreen = null },
                            viewModel = maintenanceViewModel,
                        )
                    }
                    SettingsSubScreen.ReadaloudSettings -> ReadaloudSettingsScreen(
                        onNavigateBack = { settingsSubScreen = null },
                        onNavigateToAddSource = { backend, editId ->
                            settingsSubScreen = when (backend) {
                                is AddSourceBackend.Webdav -> SettingsSubScreen.WebdavAddSource(editId)
                                else -> SettingsSubScreen.AddSource(backend, editId)
                            }
                        },
                        onNavigateToReadaloudMatches = { sourceId ->
                            settingsSubScreen = SettingsSubScreen.ReadaloudMatches(sourceId)
                        },
                    )
                    is SettingsSubScreen.ReadaloudMatches -> {
                        val koin = org.koin.mp.KoinPlatform.getKoin()
                        val host = remember(sub.sourceId) { ScreenScopedViewModelHost() }
                        val matchesViewModel: ReadaloudMatchesViewModel = remember(sub.sourceId) {
                            host.adopt(koin.get { parametersOf(sub.sourceId) })
                        }
                        DisposableEffect(sub.sourceId) { onDispose { host.clear() } }
                        ReadaloudMatchesScreen(
                            onNavigateBack = { settingsSubScreen = null },
                            viewModel = matchesViewModel,
                        )
                    }
                    SettingsSubScreen.Changelog -> {
                        val changelogViewModel = koinInject<ChangelogViewModel>()
                        ChangelogScreen(
                            onNavigateBack = { settingsSubScreen = null },
                            viewModel = changelogViewModel,
                        )
                    }
                    SettingsSubScreen.DeveloperOptions -> {
                        val settingsViewModel = koinInject<SettingsViewModel>()
                        DeveloperOptionsScreen(
                            onNavigateBack = { settingsSubScreen = null },
                            onNavigateToDebugLogs = { settingsSubScreen = SettingsSubScreen.DebugLogs },
                            onShareAllCrashReports = slots.onShareAllCrashReports,
                            onShareSingleCrashReport = slots.onShareSingleCrashReport,
                            viewModel = settingsViewModel,
                        )
                    }
                    SettingsSubScreen.DebugLogs -> DebugLogScreen(
                        onNavigateBack = { settingsSubScreen = SettingsSubScreen.DeveloperOptions },
                        onShare = slots.onShareDebugLog,
                    )
                    SettingsSubScreen.DictionaryPacks -> DictionaryPacksScreen(
                        onNavigateBack = { settingsSubScreen = null },
                    )
                    null -> {
                        val settingsViewModel = koinInject<SettingsViewModel>()
                        SettingsScreen(
                            isExpandedWidth = isTabletLayout,
                            actions = SettingsActions(
                                onNavigateBack = { appSection = previousSection },
                                onNavigateToAddSource = { backend, editId ->
                                    settingsSubScreen = when (backend) {
                                        is AddSourceBackend.Webdav -> SettingsSubScreen.WebdavAddSource(editId)
                                        else -> SettingsSubScreen.AddSource(backend, editId)
                                    }
                                },
                                onNavigateToAddSourcePicker = { settingsSubScreen = SettingsSubScreen.AddSource() },
                                onNavigateToAddLocalFolder = { slots.localFilePicker?.invoke() },
                                onNavigateToReadaloudSettings = { settingsSubScreen = SettingsSubScreen.ReadaloudSettings },
                                onNavigateToAnnotationsSyncSettings = { settingsSubScreen = SettingsSubScreen.AnnotationsSync },
                                onNavigateToDeveloperOptions = { settingsSubScreen = SettingsSubScreen.DeveloperOptions },
                                onNavigateToDictionaryPacks = { settingsSubScreen = SettingsSubScreen.DictionaryPacks },
                                onNavigateToDebugLogs = { settingsSubScreen = SettingsSubScreen.DebugLogs },
                                onNavigateToChangelog = { settingsSubScreen = SettingsSubScreen.Changelog },
                                platformHooks = slots.platformSettingsHooks,
                            ),
                            viewModel = settingsViewModel,
                        )
                    }
                }
            }

            AppSection.Downloads -> {
                val downloadsViewModel = koinInject<DownloadsViewModel>()
                val downloadsApplicationScope = koinInject<ApplicationScope>()
                val downloadsRecordItemOpened = koinInject<RecordItemOpened>()
                var downloadsDetailNav by remember { mutableStateOf<LibraryNav.ItemDetail?>(null) }
                BackHandler(enabled = downloadsDetailNav != null) { downloadsDetailNav = null }
                BackHandler(enabled = downloadsDetailNav == null) { appSection = previousSection }
                val detail = downloadsDetailNav
                if (detail != null) {
                    LibraryItemDetailScreen(
                        itemId = detail.itemId,
                        sourceId = detail.sourceId,
                        actions = LibraryItemDetailActions(
                            onBack = { downloadsDetailNav = null },
                            onRead = { item ->
                                openReadItemForReading(item, downloadsApplicationScope, downloadsRecordItemOpened::invoke)
                                    ?.let {
                                        pendingLibraryNav = it
                                        appSection = AppSection.Library
                                        downloadsDetailNav = null
                                    }
                            },
                            onListen = { item ->
                                openItemForReading(item, downloadsApplicationScope, downloadsRecordItemOpened::invoke)
                                    ?.let {
                                        pendingLibraryNav = it
                                        appSection = AppSection.Library
                                        downloadsDetailNav = null
                                    }
                            },
                            onReadItemAtHref = { item, href ->
                                pendingLibraryNav = LibraryNav.Reader(item, startHref = href)
                                appSection = AppSection.Library
                                downloadsDetailNav = null
                            },
                            onListenItemAtSec = { item, sec ->
                                pendingLibraryNav = LibraryNav.AudiobookPlayer(item, startAtSec = sec)
                                appSection = AppSection.Library
                                downloadsDetailNav = null
                            },
                            onFacetSelected = NoOp.facetNavUnavailableInDownloads,
                            onNavigateToSeries = NoOp.seriesNavUnavailableInDownloads,
                        ),
                    )
                } else {
                    DownloadsScreen(
                        actions = DownloadsActions(
                            onNavigateBack = { appSection = previousSection },
                            onItemSelected = { item ->
                                downloadsDetailNav = LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })
                            },
                        ),
                        viewModel = downloadsViewModel,
                    )
                }
            }

            AppSection.Riffle -> {
                val riffleViewModel = koinInject<RiffleViewModel>()
                val riffleApplicationScope = koinInject<ApplicationScope>()
                val riffleRecordItemOpened = koinInject<RecordItemOpened>()
                val rifflePlayIntentRegistry = koinInject<AudiobookPlayIntentRegistry>()
                var riffleNavStack by remember { mutableStateOf(listOf<LibraryNav>()) }

                fun rifflePush(dest: LibraryNav) {
                    riffleNavStack = riffleNavStack + dest
                }
                fun rifflePop() {
                    riffleNavStack = riffleNavStack.dropLast(1)
                }

                SideEffect { isInReaderDestination = riffleNavStack.lastOrNull() is LibraryNav.ReaderDestination }
                DisposableEffect(Unit) { onDispose { isInReaderDestination = false } }

                BackHandler(enabled = riffleNavStack.isNotEmpty(), onBack = ::rifflePop)

                when (val current = riffleNavStack.lastOrNull()) {
                    is LibraryNav.ItemDetail -> LibraryItemDetailScreen(
                        itemId = current.itemId,
                        sourceId = current.sourceId,
                        actions = LibraryItemDetailActions(
                            onBack = ::rifflePop,
                            onRead = { item ->
                                openReadItemForReading(item, riffleApplicationScope, riffleRecordItemOpened::invoke)
                                    ?.let { rifflePush(it) }
                            },
                            onListen = { item ->
                                rifflePlayIntentRegistry.recordIntent(item.sourceId, item.id)
                                openItemForReading(item, riffleApplicationScope, riffleRecordItemOpened::invoke)
                                    ?.let { rifflePush(it) }
                            },
                            onReadItemAtHref = { item, href -> rifflePush(LibraryNav.Reader(item, startHref = href)) },
                            onListenItemAtSec = { item, sec ->
                                rifflePlayIntentRegistry.recordIntent(item.sourceId, item.id)
                                rifflePush(LibraryNav.AudiobookPlayer(item, startAtSec = sec))
                            },
                            onNavigateToSeries = { libraryId, seriesId, seriesName ->
                                rifflePush(LibraryNav.SeriesDetail(seriesId, libraryId, seriesName))
                            },
                            onFacetSelected = { facetLibraryId, facet, value ->
                                rifflePush(LibraryNav.FilteredBooks(facetLibraryId, facet, value))
                            },
                        ),
                    )
                    is LibraryNav.AnnotationReader -> AnnotationReaderLoader(
                        itemId = current.itemId,
                        sourceId = current.sourceId,
                        locatorJson = current.locatorJson,
                        rawCfi = current.rawCfi,
                        annotationId = current.annotationId,
                        onBack = ::rifflePop,
                        slots = slots,
                    )
                    is LibraryNav.ElidedReader -> ElidedReaderLoader(
                        itemId = current.itemId,
                        sourceId = current.sourceId,
                        onBack = ::rifflePop,
                        onOpenInBook = { openItem, locatorJson ->
                            rifflePop()
                            rifflePush(LibraryNav.Reader(openItem, locatorJson))
                        },
                        slots = slots,
                    )
                    is LibraryNav.FilteredBooks -> FilteredBooksHost(
                        destination = current,
                        onBack = ::rifflePop,
                        onItemSelected = { item ->
                            rifflePush(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }))
                        },
                    )
                    is LibraryNav.AnnotationSearch -> AnnotationSearchHost(
                        destination = current,
                        onBack = ::rifflePop,
                        onAnnotationSelected = { sourceId, itemId, locatorJson, rawCfi, annotationId ->
                            rifflePush(LibraryNav.AnnotationReader(itemId, sourceId.ifEmpty { null }, locatorJson, rawCfi, annotationId))
                        },
                        onOpenAudiobookBookmark = { sourceId, itemId, startAtSec ->
                            rifflePlayIntentRegistry.recordIntent(sourceId, itemId)
                            rifflePush(LibraryNav.AudiobookBookmark(itemId, sourceId.ifEmpty { null }, startAtSec))
                        },
                    )
                    is LibraryNav.SeriesDetail -> SeriesDetailScreen(
                        seriesId = current.seriesId,
                        libraryId = current.seriesLibraryId,
                        seriesName = current.seriesName,
                        onItemSelected = { item ->
                            rifflePush(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }))
                        },
                        onNavigateBack = ::rifflePop,
                    )
                    is LibraryNav.CollectionDetail -> CollectionDetailScreen(
                        collectionId = current.collectionId,
                        libraryId = current.collectionLibraryId,
                        collectionName = current.collectionName,
                        onItemSelected = { item ->
                            rifflePush(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }))
                        },
                        onNavigateBack = ::rifflePop,
                    )
                    is LibraryNav.AudiobookBookmark -> AudiobookBookmarkLoader(
                        itemId = current.itemId,
                        sourceId = current.sourceId,
                        startAtSec = current.startAtSec,
                        playerActions = PlayerHostActions(
                            onBack = ::rifflePop,
                            onPlaylistAdvance = NoOp.playlistAdvanceUnavailable,
                        ),
                        slots = slots,
                    )
                    is LibraryNav.ReaderDestination -> slots.readerHost(
                        current,
                        ReaderHostActions(
                            onBack = ::rifflePop,
                            onSwitchToAudiobook = {
                                val readerDest = current as? LibraryNav.Reader ?: return@ReaderHostActions
                                rifflePop()
                                rifflePush(LibraryNav.AudiobookPlayer(readerDest.item))
                            },
                        ),
                        PlayerHostActions(
                            onBack = ::rifflePop,
                            onPlaylistAdvance = NoOp.playlistAdvanceUnavailable,
                            onSwitchToReadaloud = { ebookItemId, atSec ->
                                scope.launch {
                                    val item = libraryObserver.getItem(ebookItemId) ?: return@launch
                                    rifflePush(LibraryNav.Reader(item, startReadaloudAtSec = atSec))
                                }
                            },
                        ),
                    )
                    else -> RiffleScreen(
                        viewModel = riffleViewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onItemSelected = { sourceId, itemId ->
                            rifflePush(LibraryNav.ItemDetail(itemId, sourceId.ifEmpty { null }))
                        },
                        onAnnotatedBookClick = { sourceId, itemId ->
                            rifflePush(LibraryNav.ElidedReader(itemId, sourceId.ifEmpty { null }))
                        },
                    )
                }
            }

            AppSection.Library -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (val dest = destination) {
                    null -> CircularProgressIndicator()
                    is HomeViewModel.StartDestination.AddSource ->
                        slots.onboardingHost(
                            { refreshKey++ },
                            { refreshKey++ },
                            false,
                            null,
                            null,
                        )
                    is HomeViewModel.StartDestination.Riffle -> {
                        LaunchedEffect(Unit) { appSection = AppSection.Riffle }
                    }
                    is HomeViewModel.StartDestination.NoLibraries -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(Res.string.ui_unable_to_connect_to_source))
                        Button(onClick = { refreshKey++ }) {
                            Text(stringResource(Res.string.ui_retry))
                        }
                    }
                    is HomeViewModel.StartDestination.Library -> {
                        LaunchedEffect(dest.libraryId) {
                            if (activeLibraryId == null) activeLibraryId = dest.libraryId
                        }
                        LibraryHost(
                            sourceType = dest.sourceType,
                            libraryId = dest.libraryId,
                            libraryName = dest.libraryName,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onReaderActiveChanged = { isInReaderDestination = it },
                            pendingNav = pendingLibraryNav,
                            onPendingNavHandled = { pendingLibraryNav = null },
                            navStack = libraryNavStack,
                            onNavStackChange = {
                                libraryNavStack = it
                                navStateHolder.libraryId = activeLibraryId
                                navStateHolder.stack = it
                            },
                            slots = slots,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun LibraryHost(
    sourceType: SourceType?,
    libraryId: String,
    libraryName: String,
    onOpenDrawer: () -> Unit,
    onReaderActiveChanged: (Boolean) -> Unit,
    pendingNav: LibraryNav? = null,
    onPendingNavHandled: () -> Unit,
    navStack: List<LibraryNav>,
    onNavStackChange: (List<LibraryNav>) -> Unit,
    slots: AppShellSlots,
) {
    val applicationScope = koinInject<ApplicationScope>()
    val recordItemOpened = koinInject<RecordItemOpened>()
    val playIntentRegistry = koinInject<AudiobookPlayIntentRegistry>()
    val libraryObserver = koinInject<LibraryObserver>()
    val unboundedType = sourceType.takeIf { shouldRenderUnboundedBrowse(it) }
    val scope = rememberCoroutineScope()

    val koin = KoinPlatform.getKoin()
    val libraryItemsHost = remember(libraryId) { ScreenScopedViewModelHost() }
    val libraryItemsViewModel = remember(libraryId) {
        libraryItemsHost.adopt(koin.get<LibraryItemsViewModel> { parametersOf(libraryId) })
    }
    val annotationsHost = remember(libraryId) { ScreenScopedViewModelHost() }
    val annotationsViewModel = remember(libraryId) {
        annotationsHost.adopt(koin.get<AnnotationsListViewModel> { parametersOf(libraryId) })
    }
    DisposableEffect(libraryId) {
        onDispose {
            libraryItemsHost.clear()
            annotationsHost.clear()
        }
    }

    LaunchedEffect(pendingNav) {
        val nav = pendingNav ?: return@LaunchedEffect
        if (!isAlreadyAtPlayerDestination(navStack, nav)) {
            onNavStackChange(navStack + nav)
        }
        onPendingNavHandled()
    }

    fun push(dest: LibraryNav) {
        onNavStackChange(navStack + dest)
    }
    fun pop() {
        onNavStackChange(navStack.dropLast(1).ifEmpty { listOf(LibraryNav.Items) })
    }

    BackHandler(enabled = navStack.size > 1, onBack = ::pop)

    val saveableStateHolder = rememberSaveableStateHolder()

    SideEffect {
        val last = navStack.last()
        onReaderActiveChanged(
            last is LibraryNav.Reader ||
                last is LibraryNav.PdfReader ||
                last is LibraryNav.CbzReader ||
                last is LibraryNav.ElidedReader,
        )
    }
    DisposableEffect(Unit) { onDispose { onReaderActiveChanged(false) } }

    when (val current = navStack.last()) {
        is LibraryNav.Items -> saveableStateHolder.SaveableStateProvider("$libraryId/items") {
            if (unboundedType != null) {
                UnboundedBrowseScreen(
                    sourceType = unboundedType,
                    libraryId = libraryId,
                    libraryName = libraryName,
                    onOpenDrawer = onOpenDrawer,
                    onOpenDetail = { itemId -> push(LibraryNav.ItemDetail(itemId, null)) },
                    onAnnotatedBookSelected = { sourceId, itemId ->
                        push(LibraryNav.ElidedReader(itemId, sourceId.ifEmpty { null }))
                    },
                )
            } else {
                LibraryItemsScreen(
                    libraryId = libraryId,
                    libraryName = libraryName,
                    showRecentlyAdded = sourceType?.isWebSource != true,
                    viewModel = libraryItemsViewModel,
                    annotationsViewModel = annotationsViewModel,
                    actions = LibraryItemsActions(
                        onOpenDrawer = onOpenDrawer,
                        onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
                        onAnnotatedBookSelected = { sourceId, itemId ->
                            push(LibraryNav.ElidedReader(itemId, sourceId.ifEmpty { null }))
                        },
                        onAnnotationSelected = { result ->
                            val locatorJson = annotationLocatorJson(
                                result.annotation.chapterHref,
                                result.annotation.cfi,
                                result.annotation.progression,
                            )
                            onNavStackChange(reduceNavStack(
                                NavigationIntent.OpenAnnotationReader(
                                    itemId = result.annotation.itemId,
                                    sourceId = result.annotation.sourceId.ifEmpty { null },
                                    locatorJson = locatorJson,
                                    rawCfi = result.annotation.cfi,
                                    annotationId = result.annotation.id,
                                ),
                                navStack,
                            ))
                        },
                        onAudiobookBookmarkSelected = { result ->
                            playIntentRegistry.recordIntent(result.bookmark.sourceId, result.bookmark.itemId)
                            val bookmarkSourceId = result.bookmark.sourceId.ifEmpty { null }
                            onNavStackChange(reduceNavStack(
                                NavigationIntent.OpenAudiobookBookmark(result.bookmark.itemId, bookmarkSourceId, result.bookmark.positionSec),
                                navStack,
                            ))
                        },
                        onSeriesSelected = { series ->
                            push(LibraryNav.SeriesDetail(seriesId = series.id, seriesLibraryId = series.libraryId, seriesName = series.name))
                        },
                        onCollectionSelected = { collection ->
                            push(
                                LibraryNav.CollectionDetail(
                                    collectionId = collection.id,
                                    collectionLibraryId = collection.libraryId,
                                    collectionName = collection.name,
                                ),
                            )
                        },
                        onSectionSeeMore = { sectionType -> push(LibraryNav.Section(sectionType)) },
                        onPlaylistSelected = { playlist ->
                            push(
                                LibraryNav.PlaylistDetail(
                                    playlistId = playlist.id, playlistName = playlist.name,
                                    playlistLibraryId = playlist.rootId.ifEmpty {
                                        libraryId
                                    }
                                )
                            )
                        },
                        onShowAllAnnotations = { query -> push(LibraryNav.AnnotationSearch(libraryId, query)) },
                        onNavigateBack = NoOp.backHandledByIosHost,
                    ),
                )
            }
        }
        is LibraryNav.Section -> LibrarySectionScreen(
            libraryId = libraryId,
            sectionType = current.sectionType,
            onNavigateBack = ::pop,
            onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
        )
        is LibraryNav.ItemDetail -> {
            var showEditMetadataDialog by remember(current) { mutableStateOf(false) }
            LibraryItemDetailScreen(
                itemId = current.itemId,
                sourceId = current.sourceId,
                actions = LibraryItemDetailActions(
                    onBack = ::pop,
                    onRead = { item ->
                        val newStack = reduceNavStack(NavigationIntent.ReadItem(item), navStack)
                        if (newStack.size > navStack.size) {
                            applicationScope.launchSurvivable { runCatching { recordItemOpened(item.id) } }
                            onNavStackChange(newStack)
                        }
                    },
                    onListen = { item ->
                        playIntentRegistry.recordIntent(item.sourceId, item.id)
                        val newStack = reduceNavStack(NavigationIntent.ListenItem(item), navStack)
                        if (newStack.size > navStack.size) {
                            applicationScope.launchSurvivable { runCatching { recordItemOpened(item.id) } }
                            onNavStackChange(newStack)
                        }
                    },
                    onReadItemAtHref = { item, href ->
                        val newStack = reduceNavStack(NavigationIntent.ReadItemAtHref(item, href), navStack)
                        if (newStack.size > navStack.size) {
                            applicationScope.launchSurvivable { runCatching { recordItemOpened(item.id) } }
                            onNavStackChange(newStack)
                        }
                    },
                    onListenItemAtSec = { item, sec ->
                        playIntentRegistry.recordIntent(item.sourceId, item.id)
                        val newStack = reduceNavStack(NavigationIntent.ListenItemAtSec(item, sec), navStack)
                        if (newStack.size > navStack.size) {
                            applicationScope.launchSurvivable { runCatching { recordItemOpened(item.id) } }
                            onNavStackChange(newStack)
                        }
                    },
                    onNavigateToSeries = { libId, seriesId, seriesName ->
                        push(LibraryNav.SeriesDetail(seriesId, libId, seriesName))
                    },
                    onFacetSelected = { facetLibraryId, facet, value ->
                        push(LibraryNav.FilteredBooks(facetLibraryId, facet, value))
                    },
                    onEditMetadata = if (slots.editLocalFileMetadataDialog != null) {
                        { showEditMetadataDialog = true }
                    } else null,
                ),
            )
            if (showEditMetadataDialog) {
                slots.editLocalFileMetadataDialog?.invoke(current.itemId, current.sourceId) {
                    showEditMetadataDialog = false
                }
            }
        }
        is LibraryNav.FilteredBooks -> FilteredBooksHost(
            destination = current,
            onBack = ::pop,
            onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
        )
        is LibraryNav.AnnotationSearch -> AnnotationSearchHost(
            destination = current,
            onBack = ::pop,
            onAnnotationSelected = { sourceId, itemId, locatorJson, rawCfi, annotationId ->
                push(LibraryNav.AnnotationReader(itemId, sourceId.ifEmpty { null }, locatorJson, rawCfi, annotationId))
            },
            onOpenAudiobookBookmark = { sourceId, itemId, startAtSec ->
                playIntentRegistry.recordIntent(sourceId, itemId)
                push(LibraryNav.AudiobookBookmark(itemId, sourceId.ifEmpty { null }, startAtSec))
            },
        )
        is LibraryNav.AnnotationReader -> AnnotationReaderLoader(
            itemId = current.itemId,
            sourceId = current.sourceId,
            locatorJson = current.locatorJson,
            rawCfi = current.rawCfi,
            annotationId = current.annotationId,
            onBack = ::pop,
            slots = slots,
        )
        is LibraryNav.ElidedReader -> ElidedReaderLoader(
            itemId = current.itemId,
            sourceId = current.sourceId,
            onBack = ::pop,
            onOpenInBook = { openItem, locatorJson ->
                pop()
                push(LibraryNav.Reader(openItem, locatorJson))
            },
            slots = slots,
        )
        is LibraryNav.ReaderDestination -> {
            var advanceToItemId by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(advanceToItemId) {
                val nextItemId = advanceToItemId ?: return@LaunchedEffect
                val context = current as? LibraryNav.AudiobookPlayer ?: return@LaunchedEffect
                val rootId = context.playlistLibraryId ?: return@LaunchedEffect
                advanceToItemId = null
                val next = libraryObserver.observeLibraryItems(rootId).first()
                    .firstOrNull { it.id == nextItemId }
                playIntentRegistry.recordIntent(context.item.sourceId, nextItemId)
                val nextNav = playlistAdvanceNav(next, context)
                onNavStackChange(navStack.dropLast(1) + nextNav)
            }
            slots.readerHost(
                current,
                ReaderHostActions(
                    onBack = ::pop,
                    onSwitchToAudiobook = {
                        val readerDest = current as? LibraryNav.Reader ?: return@ReaderHostActions
                        onNavStackChange(navStack.dropLast(1) + LibraryNav.AudiobookPlayer(readerDest.item))
                    },
                    onOpenInBook = { locatorJson ->
                        onNavStackChange(navStack.dropLast(1) + LibraryNav.Reader(
                            item = current.item,
                            initialLocatorJson = locatorJson,
                        ))
                    },
                ),
                PlayerHostActions(
                    onBack = ::pop,
                    onPlaylistAdvance = { _, nextItemId -> advanceToItemId = nextItemId },
                    onSwitchToReadaloud = { ebookItemId, atSec ->
                        scope.launch {
                            val item = libraryObserver.getItem(ebookItemId) ?: return@launch
                            onNavStackChange(navStack + LibraryNav.Reader(item, startReadaloudAtSec = atSec))
                        }
                    },
                ),
            )
        }
        is LibraryNav.SeriesDetail -> SeriesDetailScreen(
            seriesId = current.seriesId,
            libraryId = current.seriesLibraryId,
            seriesName = current.seriesName,
            onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
            onNavigateBack = ::pop,
        )
        is LibraryNav.CollectionDetail -> CollectionDetailScreen(
            collectionId = current.collectionId,
            libraryId = current.collectionLibraryId,
            collectionName = current.collectionName,
            onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
            onNavigateBack = ::pop,
        )
        is LibraryNav.PlaylistDetail -> PlaylistDetailHost(
            destination = current,
            onBack = ::pop,
            onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
            onPlayItem = { item ->
                playIntentRegistry.recordIntent(item.sourceId, item.id)
                push(playlistPlayerNav(item, current))
            },
        )
        is LibraryNav.AudiobookBookmark -> AudiobookBookmarkLoader(
            itemId = current.itemId,
            sourceId = current.sourceId,
            startAtSec = current.startAtSec,
            playerActions = PlayerHostActions(
                onBack = ::pop,
                onPlaylistAdvance = NoOp.playlistAdvanceUnavailable,
            ),
            slots = slots,
        )
    }
}

@Composable
internal fun ElidedReaderLoader(
    itemId: String,
    sourceId: String?,
    onBack: () -> Unit,
    onOpenInBook: ((item: LibraryItem, locatorJson: String) -> Unit)? = null,
    slots: AppShellSlots,
) {
    val libraryObserver = koinInject<LibraryObserver>()
    var item by remember { mutableStateOf<LibraryItem?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(itemId, sourceId) {
        item = if (sourceId != null) libraryObserver.getItem(sourceId, itemId) else libraryObserver.getItem(itemId)
        loaded = true
    }
    val loadedItem = item
    when {
        loadedItem != null -> slots.readerHost(
            LibraryNav.Reader(loadedItem, readerSource = ReaderSource.Highlights),
            ReaderHostActions(
                onBack = onBack,
                onOpenInBook = onOpenInBook?.let { cb -> { locatorJson -> cb(loadedItem, locatorJson) } },
            ),
            PlayerHostActions(
                onBack = onBack,
                onPlaylistAdvance = NoOp.playlistAdvanceUnavailable,
            ),
        )
        loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.ui_book_not_found))
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
internal fun AnnotationReaderLoader(
    itemId: String,
    sourceId: String?,
    locatorJson: String,
    rawCfi: String = "",
    annotationId: String? = null,
    onBack: () -> Unit,
    slots: AppShellSlots,
) {
    val libraryObserver = koinInject<LibraryObserver>()
    var item by remember { mutableStateOf<LibraryItem?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(itemId, sourceId) {
        item = if (sourceId != null) libraryObserver.getItem(sourceId, itemId) else libraryObserver.getItem(itemId)
        loaded = true
    }
    val loadedItem = item
    when {
        loadedItem != null -> slots.readerHost(
            LibraryNav.Reader(loadedItem, initialLocatorJson = locatorJson, openAtRawCfi = rawCfi.ifEmpty { null }, openAtAnnotationId = annotationId),
            ReaderHostActions(onBack = onBack),
            PlayerHostActions(onBack = onBack, onPlaylistAdvance = NoOp.playlistAdvanceUnavailable),
        )
        loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.ui_book_not_found))
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
internal fun AudiobookBookmarkLoader(
    itemId: String,
    sourceId: String?,
    startAtSec: Double,
    playerActions: PlayerHostActions,
    slots: AppShellSlots,
) {
    val libraryObserver = koinInject<LibraryObserver>()
    var item by remember { mutableStateOf<LibraryItem?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(itemId, sourceId) {
        item = if (sourceId != null) libraryObserver.getItem(sourceId, itemId) else libraryObserver.getItem(itemId)
        loaded = true
    }
    val loadedItem = item
    when {
        loadedItem != null -> slots.readerHost(
            LibraryNav.AudiobookPlayer(loadedItem, startAtSec = startAtSec),
            ReaderHostActions(onBack = playerActions.onBack),
            playerActions,
        )
        loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.ui_book_not_found))
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
internal fun FilteredBooksHost(
    destination: LibraryNav.FilteredBooks,
    onBack: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
) {
    val koin = org.koin.mp.KoinPlatform.getKoin()
    val key = "${destination.facetLibraryId}/${destination.facetType}/${destination.facetValue}"
    val host = remember(key) { ScreenScopedViewModelHost() }
    val viewModel: FilteredBooksViewModel = remember(key) {
        host.adopt(koin.get { parametersOf(destination.facetLibraryId, destination.facetType.name, destination.facetValue) })
    }
    DisposableEffect(key) { onDispose { host.clear() } }
    FilteredBooksScreen(
        viewModel = viewModel,
        labels = filteredBooksLabels(),
        minCellSize = coverGridMinCell(),
        onItemSelected = onItemSelected,
        onNavigateBack = onBack,
        tileContent = { item, token, onClick -> BookCoverTile(item = item, token = token, onClick = onClick) },
    )
}

@Composable
internal fun AnnotationSearchHost(
    destination: LibraryNav.AnnotationSearch,
    onBack: () -> Unit,
    onAnnotationSelected: (sourceId: String, itemId: String, locatorJson: String, rawCfi: String, annotationId: String?) -> Unit,
    onOpenAudiobookBookmark: (sourceId: String, itemId: String, startAtSec: Double) -> Unit,
) {
    val koin = org.koin.mp.KoinPlatform.getKoin()
    val key = "${destination.searchLibraryId}/${destination.query}"
    val host = remember(key) { ScreenScopedViewModelHost() }
    val viewModel: AnnotationSearchViewModel = remember(key) {
        host.adopt(koin.get { parametersOf(destination.searchLibraryId, destination.query) })
    }
    DisposableEffect(key) { onDispose { host.clear() } }
    AnnotationSearchResultsScreen(
        viewModel = viewModel,
        labels = annotationSearchLabels(),
        onNavigateBack = onBack,
        onAnnotationSelected = { result ->
            val locatorJson = annotationLocatorJson(
                result.annotation.chapterHref,
                result.annotation.cfi,
                result.annotation.progression,
            )
            onAnnotationSelected(result.annotation.sourceId, result.annotation.itemId, locatorJson, result.annotation.cfi, result.annotation.id)
        },
        onAudiobookBookmarkSelected = { result ->
            onOpenAudiobookBookmark(result.bookmark.sourceId, result.bookmark.itemId, result.bookmark.positionSec)
        },
    )
}

@Composable
private fun PlaylistDetailHost(
    destination: LibraryNav.PlaylistDetail,
    onBack: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    onPlayItem: (LibraryItem) -> Unit,
) {
    val koin = org.koin.mp.KoinPlatform.getKoin()
    val key = destination.playlistLibraryId + "/" + destination.playlistId
    val host = remember(key) { ScreenScopedViewModelHost() }
    val viewModel: PlaylistDetailViewModel = remember(key) {
        host.adopt(koin.get { parametersOf(destination.playlistLibraryId, destination.playlistId, destination.playlistName) })
    }
    DisposableEffect(key) { onDispose { host.clear() } }
    PlaylistDetailScreen(
        viewModel = viewModel,
        labels = playlistLabels(),
        onNavigateBack = onBack,
        onItemSelected = onItemSelected,
        onPlayItem = onPlayItem,
        itemContent = { item, _, onClick -> PlaylistItemRow(item = item, onClick = onClick) },
    )
}
