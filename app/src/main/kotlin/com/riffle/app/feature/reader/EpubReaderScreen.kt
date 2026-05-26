package com.riffle.app.feature.reader

import android.content.res.Configuration
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.riffle.app.ui.theme.RiffleTheme
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: EpubReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val formattingPrefs by viewModel.formattingPreferences.collectAsState()
    val hasBookOverrides by viewModel.hasBookOverrides.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val volumeKeyNavigationEnabled by viewModel.volumeKeyNavigationEnabled.collectAsState()
    val invertVolumeKeys by viewModel.invertVolumeKeys.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showFormattingPanel by remember { mutableStateOf(false) }
    val immersiveState = rememberImmersiveModeState()

    // Close reading session when screen is disposed (navigation away)
    DisposableEffect(viewModel) {
        onDispose { viewModel.onReaderClosed() }
    }

    // Pause/resume periodic sync with app lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onReaderClosed()
                Lifecycle.Event.ON_START -> viewModel.onReaderResumed()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        // If already started (e.g. after config change), restart sync explicitly
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.onReaderResumed()
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Screen wake lock — gated on user preference
    DisposableEffect(keepScreenOn) {
        val window = (context as? FragmentActivity)?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Prevent window resizing on keyboard show/hide — avoids layout jumps when dismissing
    // the search keyboard while the reader is open.
    DisposableEffect(Unit) {
        val window = (context as? FragmentActivity)?.window
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED)
        }
    }

    // Toast on sync error
    LaunchedEffect(viewModel) {
        viewModel.syncErrorEvents.collect {
            Toast.makeText(context, "Could not sync reading progress", Toast.LENGTH_SHORT).show()
        }
    }

    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val currentSearchIndex by viewModel.currentSearchIndex.collectAsState()
    val title = (state as? ReaderState.Ready)?.title ?: ""
    val tocVisible by viewModel.tocVisible.collectAsState()

    // TopAppBar floats as an overlay so its show/hide never resizes the content area —
    // eliminates the compound flicker that Scaffold's topBar slot caused by reflowing the
    // WebView simultaneously with the system-bar animation.
    Box(modifier = Modifier.fillMaxSize()) {
        // navigationBarsPadding only — status bar insets are omitted because in immersive
        // mode the status bar is hidden, and the floating TopAppBar carries its own
        // TopAppBarDefaults.windowInsets when the user taps to reveal it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            when (val s = state) {
                ReaderState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("reader_loading"),
                    )
                }
                is ReaderState.Ready -> {
                    val locatorHref by viewModel.currentLocatorHref.collectAsState()
                    val tocEntries by viewModel.tocEntries.collectAsState()
                    LaunchedEffect(tocVisible, showFormattingPanel) {
                        if (tocVisible || showFormattingPanel) viewModel.closeSearch()
                        viewModel.onPanelStateChanged(tocVisible || showFormattingPanel)
                    }
                    EpubNavigatorView(
                        state = s,
                        formattingPrefs = formattingPrefs,
                        onPositionChanged = { locator ->
                            if (!isSearchActive) immersiveState.dismissOverlay()
                            viewModel.onPositionChanged(locator)
                        },
                        onNavigationEvents = viewModel.navigationEvents,
                        serverLocatorEvents = viewModel.serverLocatorEvents,
                        searchNavigationEvents = viewModel.searchNavigationEvents,
                        searchResults = searchResults,
                        currentSearchIndex = currentSearchIndex,
                        volumeNavEvents = viewModel.volumeNavEvents,
                        onTap = immersiveState::toggle,
                        latestLocator = { viewModel.latestLocator },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("reader_ready")
                            .semantics {
                                contentDescription = buildString {
                                    append(locatorHref ?: "")
                                    append(" theme:")
                                    append(formattingPrefs.theme.name.lowercase())
                                    append(" wake-lock:")
                                    append(if (keepScreenOn) "on" else "off")
                                }
                            },
                    )
                    if (tocVisible) {
                        TocPanel(
                            entries = tocEntries,
                            activeHref = locatorHref,
                            onEntryClick = viewModel::navigateToEntry,
                            onDismiss = viewModel::closeToc,
                        )
                    }
                    if (formattingPrefs.showChapterMap) {
                        // Rail state (cursorPosition changes at scroll framerate) is isolated
                        // inside EpubChapterRailOverlay so EpubNavigatorView is not in the
                        // same recomposition scope and does not recompose on every scroll event.
                        EpubChapterRailOverlay(
                            viewModel = viewModel,
                            darkTheme = formattingPrefs.theme == ReaderTheme.Dark,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
                is ReaderState.Error -> {
                    Text(
                        s.message,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("reader_error_state"),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !immersiveState.isImmersive,
            enter = slideInVertically(initialOffsetY = { -it }) + expandVertically(expandFrom = Alignment.Top),
            exit = slideOutVertically(targetOffsetY = { -it }) + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            if (isSearchActive) {
                SearchTopBar(
                    query = searchQuery,
                    resultCount = searchResults.size,
                    currentIndex = currentSearchIndex,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    onPrev = viewModel::prevSearchResult,
                    onNext = viewModel::nextSearchResult,
                    onClose = viewModel::closeSearch,
                    onNavigateBack = onNavigateBack,
                )
            } else {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (state is ReaderState.Ready) {
                            IconButton(onClick = viewModel::openSearch) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = viewModel::openToc) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Table of Contents")
                            }
                            IconButton(onClick = { showFormattingPanel = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Format")
                            }
                        }
                    },
                )
            }
        }

        if (showFormattingPanel) {
            FormattingPanel(
                prefs = formattingPrefs,
                hasBookOverrides = hasBookOverrides,
                onPrefsChange = { viewModel.updateFormatting(it) },
                onReset = { viewModel.resetToGlobalDefaults() },
                onDismiss = { showFormattingPanel = false },
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
                volumeKeyNavigationEnabled = volumeKeyNavigationEnabled,
                onVolumeKeyNavigationEnabledChange = { viewModel.setVolumeKeyNavigationEnabled(it) },
                invertVolumeKeys = invertVolumeKeys,
                onInvertVolumeKeysChange = { viewModel.setInvertVolumeKeys(it) },
            )
        }
    }
}

// Isolated scope: cursorPosition updates only recompose this composable, not sibling EpubNavigatorView.
@Composable
private fun BoxScope.EpubChapterRailOverlay(
    viewModel: EpubReaderViewModel,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val railSegments by viewModel.railSegments.collectAsState()
    val activeRailSegmentIndex by viewModel.activeRailSegmentIndex.collectAsState()
    val cursorPosition by viewModel.railCursorPosition.collectAsState()
    RiffleTheme(darkTheme = darkTheme) {
        ChapterNavigationRail(
            segments = railSegments,
            activeIndex = activeRailSegmentIndex,
            cursorPosition = cursorPosition,
            onSegmentClick = viewModel::navigateToSegment,
            modifier = modifier,
        )
    }
}

// Singleton so the EpubNavigatorFactory only creates one DataStore for formatting_preferences
// per process — creating multiple instances triggers a DataStore "multiple active" crash.
private val sharedEpubNavigatorConfig by lazy { EpubNavigatorFactory.Configuration() }

@OptIn(ExperimentalReadiumApi::class)
@Composable
private fun EpubNavigatorView(
    state: ReaderState.Ready,
    formattingPrefs: FormattingPreferences,
    onPositionChanged: (Locator) -> Unit,
    onNavigationEvents: Flow<Link>,
    serverLocatorEvents: Flow<Locator>,
    searchNavigationEvents: Flow<Locator>,
    searchResults: List<Locator>,
    currentSearchIndex: Int,
    volumeNavEvents: Flow<VolumeNavEvent>,
    onTap: () -> Unit,
    latestLocator: () -> Locator?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isFixedLayout = state.publication.metadata.presentation.layout == EpubLayout.FIXED
    val coroutineScope = rememberCoroutineScope()
    val fragmentRef = remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val containerRef = remember { mutableStateOf<ScrollBoundaryNavigationContainer?>(null) }
    // Non-State holder for current href — written by the locator coroutine, read inside
    // navigation callbacks. Using a plain array avoids triggering recomposition on scroll.
    val currentHrefHolder = remember { arrayOf<String?>(null) }
    // Tracks the isDoublePage value the current fragment was created with; null = no fragment.
    // Plain array (not MutableState) to avoid triggering recomposition.
    val fragmentDoublePageHolder = remember { arrayOf<Boolean?>(null) }


    val currentFormattingPrefs by rememberUpdatedState(formattingPrefs)

    // rememberUpdatedState ensures the listener always calls the latest onTap lambda
    // without needing to be re-created when onTap changes.
    val currentOnTap by rememberUpdatedState(onTap)
    val tapListener = remember {
        object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                currentOnTap()
                return false
            }
        }
    }

    LaunchedEffect(onNavigationEvents) {
        onNavigationEvents.collect { link ->
            fragmentRef.value?.go(link)
        }
    }

    LaunchedEffect(serverLocatorEvents) {
        serverLocatorEvents.collect { locator ->
            fragmentRef.value?.go(locator)
        }
    }

    LaunchedEffect(searchNavigationEvents) {
        searchNavigationEvents.collect { locator ->
            fragmentRef.value?.go(locator)
        }
    }

    // Tracks whether we have live search decorations painted in the WebView.
    // Prevents calling applyDecorations on initial composition (before WebView content loads),
    // which crashes the WebView renderer via a premature JS evaluation. See ADR 0015.
    val hasActiveDecorations = remember { mutableStateOf(false) }

    LaunchedEffect(searchResults, currentSearchIndex) {
        if (searchResults.isEmpty()) {
            if (!hasActiveDecorations.value) return@LaunchedEffect
            val fragment = fragmentRef.value as? DecorableNavigator ?: return@LaunchedEffect
            // applyDecorations calls evaluateJavascript which requires the main thread.
            // Compose test infrastructure (FrameDeferringContinuationInterceptor) can resume
            // LaunchedEffect coroutines on DefaultDispatcher; withContext(Main) ensures safety.
            withContext(Dispatchers.Main) {
                fragment.applyDecorations(emptyList(), group = "search")
            }
            hasActiveDecorations.value = false
            return@LaunchedEffect
        }
        val fragment = fragmentRef.value as? DecorableNavigator ?: return@LaunchedEffect
        val decorations = searchResults.mapIndexed { index, locator ->
            Decoration(
                id = "search_$index",
                locator = locator,
                style = if (index == currentSearchIndex)
                    Decoration.Style.Highlight(tint = android.graphics.Color.parseColor("#FFF5A623"))
                else
                    Decoration.Style.Highlight(tint = android.graphics.Color.parseColor("#FFFDE68A")),
            )
        }
        withContext(Dispatchers.Main) {
            fragment.applyDecorations(decorations, group = "search")
        }
        hasActiveDecorations.value = true
    }

    LaunchedEffect(volumeNavEvents) {
        volumeNavEvents.collect { event ->
            val fragment = fragmentRef.value ?: return@collect
            val container = containerRef.value
            if (currentFormattingPrefs.orientation == ReaderOrientation.Vertical && container != null) {
                when (event) {
                    VolumeNavEvent.Forward -> {
                        val atBottom = fragment.evaluateJavascript(
                            "(window.scrollY + window.innerHeight >= document.body.scrollHeight - 4).toString()"
                        )?.trim('"') == "true"
                        container.handleVolumeScroll(forward = true, atBoundary = atBottom) { js ->
                            launch { fragment.evaluateJavascript(js) }
                        }
                    }
                    VolumeNavEvent.Backward -> {
                        val atTop = fragment.evaluateJavascript(
                            "(window.scrollY <= 4).toString()"
                        )?.trim('"') == "true"
                        container.handleVolumeScroll(forward = false, atBoundary = atTop) { js ->
                            launch { fragment.evaluateJavascript(js) }
                        }
                    }
                }
            } else {
                when (event) {
                    VolumeNavEvent.Forward -> fragment.goForward(animated = false)
                    VolumeNavEvent.Backward -> fragment.goBackward(animated = false)
                }
            }
        }
    }

    // fragmentRef.value is a key so the effect re-fires when the fragment becomes available
    // after rotation (isLandscape changes while fragmentRef is null, so the call would be lost).
    LaunchedEffect(formattingPrefs, isLandscape, fragmentRef.value) {
        fragmentRef.value?.submitPreferences(formattingPrefs.toEpubPreferences(isLandscape, isFixedLayout))
    }

    DisposableEffect(tapListener) {
        onDispose { fragmentRef.value?.removeInputListener(tapListener) }
    }

    // Remove the fragment from FM when navigating away (not on rotation) so it doesn't
    // linger in saved state and crash restoration on the next configuration change.
    DisposableEffect(Unit) {
        onDispose {
            if (!fragmentActivity.isChangingConfigurations) {
                fragmentRef.value?.let { frag ->
                    runCatching {
                        fragmentActivity.supportFragmentManager
                            .beginTransaction()
                            .remove(frag)
                            .commitNowAllowingStateLoss()
                    }
                }
            }
        }
    }

    val containerId = rememberSaveable { View.generateViewId() }

    AndroidView(
        factory = { ctx ->
            ScrollBoundaryNavigationContainer(ctx).apply {
                // Compose handles all inset-based padding (navigationBarsPadding on the outer
                // Box, TopAppBarDefaults.windowInsets on the floating TopAppBar). Consuming
                // insets here prevents Readium's WebViews from applying status-bar padding,
                // which on physical devices remains non-zero even after controller.hide().
                ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ -> WindowInsetsCompat.CONSUMED }
                val fragmentContainer = FragmentContainerView(ctx).apply { id = containerId }
                addView(fragmentContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }.also { containerRef.value = it }
        },
        update = { container ->
            container.isScrollMode = formattingPrefs.orientation == ReaderOrientation.Vertical

            val fragmentContainer = container.getChildAt(0) as? FragmentContainerView
                ?: return@AndroidView
            val fm = fragmentActivity.supportFragmentManager

            // RS properties (colCount/colWidth) are baked into the fragment at creation time and
            // cannot be changed via submitPreferences. Recreate the fragment whenever the
            // double-page mode changes so the new RS config takes effect.
            val isDoublePage = !isFixedLayout &&
                formattingPrefs.orientation != ReaderOrientation.Vertical &&
                formattingPrefs.doublePageSpread &&
                isLandscape
            val existingFrag = fragmentRef.value
            if (existingFrag != null && fragmentDoublePageHolder[0] != isDoublePage) {
                fm.beginTransaction().remove(existingFrag).commitNow()
                fragmentRef.value = null
                fragmentDoublePageHolder[0] = null
            }

            // After Activity recreation, the FragmentManager may have restored an
            // EpubNavigatorFragment using the default factory (not EpubNavigatorFactory).
            // Without the factory, the fragment's WebView cannot connect to the Readium
            // streaming server. Remove any such stale fragment so the creation path below
            // can recreate it properly with latestLocator() as the initial position.
            if (fragmentRef.value == null) {
                fm.findFragmentById(containerId)?.let { stale ->
                    fm.beginTransaction().remove(stale).commitNow()
                }
            }

            if (fm.findFragmentById(containerId) == null) {
                val fragmentFactory = EpubNavigatorFactory(
                    publication = state.publication,
                    configuration = sharedEpubNavigatorConfig,
                ).createFragmentFactory(
                    initialLocator = latestLocator() ?: state.initialLocator,
                    initialPreferences = formattingPrefs.toEpubPreferences(isLandscape, isFixedLayout),
                    configuration = formattingPrefs.toFragmentConfiguration(isLandscape, isFixedLayout),
                )
                fm.fragmentFactory = fragmentFactory
                fm.beginTransaction()
                    .add(containerId, EpubNavigatorFragment::class.java, null)
                    .commitNow()
                val fragment = fm.findFragmentById(containerId) as? EpubNavigatorFragment
                    ?: return@AndroidView
                fragmentRef.value = fragment
                fragmentDoublePageHolder[0] = isDoublePage
                fragment.addInputListener(tapListener)
                coroutineScope.launch {
                    fragment.currentLocator.collect { locator ->
                        container.currentProgression = locator.locations.progression?.toFloat() ?: 0f
                        currentHrefHolder[0] = locator.href.toString()
                        onPositionChanged(locator)
                    }
                }
            }

            fragmentRef.value?.let { fragment ->
                container.onNavigateForward = navigateForward@{
                    val idx = state.publication.readingOrder
                        .indexOfFirst { it.href.toString() == currentHrefHolder[0] }
                    if (idx < 0 || idx >= state.publication.readingOrder.size - 1) return@navigateForward
                    fragment.goForward(animated = false)
                }
                container.onNavigateBackward = navigateBackward@{
                    val idx = state.publication.readingOrder
                        .indexOfFirst { it.href.toString() == currentHrefHolder[0] }
                    if (idx <= 0) return@navigateBackward
                    val prevLink = state.publication.readingOrder[idx - 1]
                    val locator = Locator.fromJSON(
                        JSONObject()
                            .put("href", prevLink.href.toString())
                            .put("type", "application/xhtml+xml")
                            .put("locations", JSONObject().put("progression", 1.0))
                    ) ?: return@navigateBackward
                    coroutineScope.launch {
                        fragment.go(locator, animated = false)
                    }
                }
            }
        },
        modifier = modifier,
    )
}
