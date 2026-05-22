@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFactory
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
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

    // Screen wake lock — gated on user preference
    DisposableEffect(keepScreenOn) {
        val window = (context as? FragmentActivity)?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val title = (state as? ReaderState.Ready)?.title ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
                    val currentPage by viewModel.currentPage.collectAsState()
                    PdfNavigatorView(
                        state = s,
                        onPageChanged = viewModel::onPageChanged,
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
    }
}

@Composable
private fun PdfNavigatorView(
    state: ReaderState.Ready,
    onPageChanged: (Locator) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return

    AndroidView(
        factory = { ctx ->
            FragmentContainerView(ctx).apply { id = View.generateViewId() }
        },
        update = { containerView ->
            val fm = fragmentActivity.supportFragmentManager
            if (fm.findFragmentById(containerView.id) == null) {
                val fragmentFactory = PdfiumNavigatorFactory(
                    publication = state.publication,
                    pdfEngineProvider = PdfiumEngineProvider(),
                ).createFragmentFactory(
                    initialLocator = state.initialLocator,
                )
                fm.fragmentFactory = fragmentFactory
                fm.beginTransaction()
                    .add(containerView.id, PdfiumNavigatorFragment::class.java, null)
                    .commitNow()
                val fragment = fm.findFragmentById(containerView.id) as? PdfiumNavigatorFragment
                    ?: return@AndroidView
                // Enable tap-to-navigate: PDFs are always in scroll mode, so we must
                // explicitly opt in to handling taps while scrolling.
                fragment.addInputListener(
                    DirectionalNavigationAdapter(
                        navigator = fragment,
                        handleTapsWhileScrolling = true,
                    )
                )
                fragmentActivity.lifecycleScope.launch {
                    fragment.currentLocator.collect { locator -> onPageChanged(locator) }
                }
            }
        },
        modifier = modifier,
    )
}
