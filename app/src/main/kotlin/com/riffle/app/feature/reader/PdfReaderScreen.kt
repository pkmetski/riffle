@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFactory
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Locator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val immersiveState = rememberImmersiveModeState()

    DisposableEffect(viewModel) {
        onDispose { viewModel.onReaderClosed() }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onReaderClosed()
                Lifecycle.Event.ON_START -> viewModel.onReaderResumed()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
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

    LaunchedEffect(viewModel) {
        viewModel.syncErrorEvents.collect {
            Toast.makeText(context, "Could not sync reading progress", Toast.LENGTH_SHORT).show()
        }
    }

    val title = (state as? ReaderState.Ready)?.title ?: ""

    // TopAppBar floats as an overlay so its show/hide never resizes the PDF view —
    // same pattern as EpubReaderScreen.
    Box(modifier = Modifier.fillMaxSize()) {
        // Reader content is edge-to-edge: status-bar insets are consumed at the AndroidView
        // root (see ViewCompat.setOnApplyWindowInsetsListener in the PDF AndroidView factory)
        // and the nav-bar inset is intentionally NOT applied, so the PDF view keeps the same
        // height whether bars are visible or hidden. The floating TopAppBar carries its own
        // TopAppBarDefaults.windowInsets to position itself below the status bar; the system
        // nav bar overlays the bottom of the PDF view without reflowing it. See ADR 0017.
        Box(
            modifier = Modifier.fillMaxSize(),
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
                    val currentPage by viewModel.currentPage.collectAsState()
                    PdfNavigatorView(
                        state = s,
                        onPageChanged = { locator ->
                            immersiveState.dismissOverlay()
                            viewModel.onPageChanged(locator)
                        },
                        onTap = immersiveState::toggle,
                        serverLocatorEvents = viewModel.serverLocatorEvents,
                        volumeNavEvents = viewModel.volumeNavEvents,
                        latestLocator = { viewModel.latestLocator },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("reader_ready")
                            .semantics {
                                contentDescription = buildString {
                                    append(currentPage?.let { "page:$it" } ?: "")
                                    append(" wake-lock:")
                                    append(if (keepScreenOn) "on" else "off")
                                }
                            },
                    )
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
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = readerTopAppBarColors(),
            )
        }
    }
}

@Composable
private fun PdfNavigatorView(
    state: ReaderState.Ready,
    onPageChanged: (Locator) -> Unit,
    onTap: () -> Unit,
    serverLocatorEvents: Flow<Locator>,
    volumeNavEvents: Flow<VolumeNavEvent>,
    latestLocator: () -> Locator?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return
    val coroutineScope = rememberCoroutineScope()
    val fragmentRef = remember { mutableStateOf<PdfiumNavigatorFragment?>(null) }

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

    LaunchedEffect(serverLocatorEvents) {
        serverLocatorEvents.collect { locator ->
            fragmentRef.value?.go(locator)
        }
    }

    LaunchedEffect(volumeNavEvents) {
        volumeNavEvents.collect { event ->
            when (event) {
                VolumeNavEvent.Forward -> fragmentRef.value?.goForward(animated = false)
                VolumeNavEvent.Backward -> fragmentRef.value?.goBackward(animated = false)
            }
        }
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
            FragmentContainerView(ctx).apply {
                id = containerId
                // Compose handles all inset-based padding. Consuming insets here prevents
                // Readium's PDF views from applying status-bar padding on physical devices.
                ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ ->
                    WindowInsetsCompat.CONSUMED
                }
            }
        },
        update = { containerView ->
            val fm = fragmentActivity.supportFragmentManager
            // After Activity recreation, the FragmentManager may have restored a
            // PdfiumNavigatorFragment using the default factory (not PdfiumNavigatorFactory).
            // Without the factory, the fragment cannot connect to the Readium streaming server.
            // Remove any such stale fragment so the creation path below can recreate it
            // properly with latestLocator() as the initial position.
            if (fragmentRef.value == null) {
                fm.findFragmentById(containerId)?.let { stale ->
                    fm.beginTransaction().remove(stale).commitNow()
                }
            }

            if (fm.findFragmentById(containerId) == null) {
                val fragmentFactory = PdfiumNavigatorFactory(
                    publication = state.publication,
                    pdfEngineProvider = PdfiumEngineProvider(),
                ).createFragmentFactory(
                    initialLocator = latestLocator() ?: state.initialLocator,
                )
                fm.fragmentFactory = fragmentFactory
                fm.beginTransaction()
                    .add(containerId, PdfiumNavigatorFragment::class.java, null)
                    .commitNow()
                @Suppress("UNCHECKED_CAST")
                val fragment = fm.findFragmentById(containerId) as? PdfiumNavigatorFragment
                    ?: return@AndroidView
                fragmentRef.value = fragment
                // DirectionalNavigationAdapter handles edge taps for page navigation;
                // tapListener (registered after) receives only center taps that DA doesn't consume.
                fragment.addInputListener(
                    DirectionalNavigationAdapter(
                        navigator = fragment,
                        handleTapsWhileScrolling = true,
                    )
                )
                fragment.addInputListener(tapListener)
                coroutineScope.launch {
                    fragment.currentLocator.collect { locator -> onPageChanged(locator) }
                }
            }
        },
        modifier = modifier,
    )
}
