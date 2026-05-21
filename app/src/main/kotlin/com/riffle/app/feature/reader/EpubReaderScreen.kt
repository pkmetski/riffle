package com.riffle.app.feature.reader

import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import kotlinx.coroutines.flow.Flow
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
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showFormattingPanel by remember { mutableStateOf(false) }

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

    // Screen wake lock
    DisposableEffect(Unit) {
        val window = (context as? FragmentActivity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    Scaffold(
        topBar = {
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
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                    EpubNavigatorView(
                        state = s,
                        formattingPrefs = formattingPrefs,
                        onPositionChanged = viewModel::onPositionChanged,
                        onNavigationEvents = viewModel.navigationEvents,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("reader_ready")
                            .semantics {
                                contentDescription = buildString {
                                    append(locatorHref ?: "")
                                    append(" theme:")
                                    append(formattingPrefs.theme.name.lowercase())
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

// Singleton so the EpubNavigatorFactory only creates one DataStore for formatting_preferences
// per process — creating multiple instances triggers a DataStore "multiple active" crash.
private val sharedEpubNavigatorConfig by lazy { EpubNavigatorFactory.Configuration() }

@Composable
private fun EpubNavigatorView(
    state: ReaderState.Ready,
    formattingPrefs: FormattingPreferences,
    onPositionChanged: (Locator) -> Unit,
    onNavigationEvents: Flow<Link>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return
    val fragmentRef = remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    LaunchedEffect(onNavigationEvents) {
        onNavigationEvents.collect { link ->
            fragmentRef.value?.go(link)
        }
    }

    // Cancels any in-flight submitPreferences call before sending the next one.
    LaunchedEffect(formattingPrefs, fragmentRef.value) {
        fragmentRef.value?.submitPreferences(formattingPrefs.toEpubPreferences())
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
                )
                fm.fragmentFactory = fragmentFactory
                fm.beginTransaction()
                    .add(containerView.id, EpubNavigatorFragment::class.java, null)
                    .commitNow()
                val fragment = fm.findFragmentById(containerView.id) as? EpubNavigatorFragment
                    ?: return@AndroidView
                fragmentRef.value = fragment
                fragmentActivity.lifecycleScope.launch {
                    fragment.currentLocator.collect { locator -> onPositionChanged(locator) }
                }
            }
        },
        modifier = modifier,
    )
}
