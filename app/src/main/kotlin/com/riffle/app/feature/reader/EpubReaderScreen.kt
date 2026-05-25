package com.riffle.app.feature.reader

import android.view.View
import android.view.WindowManager
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
import androidx.compose.material.icons.filled.List
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.riffle.app.ui.theme.RiffleTheme
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import kotlinx.coroutines.flow.Flow
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

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

    // Toast on sync error
    LaunchedEffect(viewModel) {
        viewModel.syncErrorEvents.collect {
            Toast.makeText(context, "Could not sync reading progress", Toast.LENGTH_SHORT).show()
        }
    }

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
                        viewModel.onPanelStateChanged(tocVisible || showFormattingPanel)
                    }
                    EpubNavigatorView(
                        state = s,
                        formattingPrefs = formattingPrefs,
                        onPositionChanged = { locator ->
                            immersiveState.dismissOverlay()
                            viewModel.onPositionChanged(locator)
                        },
                        onNavigationEvents = viewModel.navigationEvents,
                        serverLocatorEvents = viewModel.serverLocatorEvents,
                        volumeNavEvents = viewModel.volumeNavEvents,
                        onTap = immersiveState::toggle,
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
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state is ReaderState.Ready) {
                        IconButton(onClick = viewModel::openToc) {
                            Icon(Icons.Filled.List, contentDescription = "Table of Contents")
                        }
                        IconButton(
                            onClick = { showFormattingPanel = true },
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Format")
                        }
                    }
                }
            )
        }

        if (showFormattingPanel) {
            FormattingPanel(
                prefs = formattingPrefs,
                hasBookOverrides = hasBookOverrides,
                onPrefsChange = { viewModel.updateFormatting(it) },
                onReset = { viewModel.resetToGlobalDefaults() },
                onDismiss = { showFormattingPanel = false },
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
    volumeNavEvents: Flow<VolumeNavEvent>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return
    val fragmentRef = remember { mutableStateOf<EpubNavigatorFragment?>(null) }

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

    LaunchedEffect(volumeNavEvents) {
        volumeNavEvents.collect { event ->
            val fragment = fragmentRef.value ?: return@collect
            if (currentFormattingPrefs.orientation == ReaderOrientation.Vertical) {
                // Scroll mode: goForward() jumps chapters; scroll the viewport by 80% instead.
                when (event) {
                    VolumeNavEvent.Forward -> fragment.evaluateJavascript("window.scrollBy(0, window.innerHeight * 0.8)")
                    VolumeNavEvent.Backward -> fragment.evaluateJavascript("window.scrollBy(0, -window.innerHeight * 0.8)")
                }
            } else {
                when (event) {
                    VolumeNavEvent.Forward -> fragment.goForward(animated = false)
                    VolumeNavEvent.Backward -> fragment.goBackward(animated = false)
                }
            }
        }
    }

    // fragmentRef.value excluded: fragment is created with initialPreferences, so adding it would call submitPreferences twice and flash.
    LaunchedEffect(formattingPrefs) {
        fragmentRef.value?.submitPreferences(formattingPrefs.toEpubPreferences())
    }

    DisposableEffect(tapListener) {
        onDispose { fragmentRef.value?.removeInputListener(tapListener) }
    }

    AndroidView(
        factory = { ctx ->
            FragmentContainerView(ctx).apply { id = View.generateViewId() }
        },
        update = { containerView ->
            val fm = fragmentActivity.supportFragmentManager
            if (fm.findFragmentById(containerView.id) == null) {
                val fragmentFactory = EpubNavigatorFactory(
                    publication = state.publication,
                    configuration = sharedEpubNavigatorConfig,
                ).createFragmentFactory(
                    initialLocator = state.initialLocator,
                    initialPreferences = formattingPrefs.toEpubPreferences(),
                )
                fm.fragmentFactory = fragmentFactory
                fm.beginTransaction()
                    .add(containerView.id, EpubNavigatorFragment::class.java, null)
                    .commitNow()
                val fragment = fm.findFragmentById(containerView.id) as? EpubNavigatorFragment
                    ?: return@AndroidView
                fragmentRef.value = fragment
                fragment.addInputListener(tapListener)
                fragmentActivity.lifecycleScope.launch {
                    fragment.currentLocator.collect { locator -> onPositionChanged(locator) }
                }
            }
        },
        modifier = modifier,
    )
}
