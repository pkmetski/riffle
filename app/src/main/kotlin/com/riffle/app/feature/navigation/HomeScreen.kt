package com.riffle.app.feature.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.riffle.core.models.SourceType
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onNavigateToAddSource: () -> Unit,
    onNavigateToLibrary: (sourceType: SourceType, libraryId: String, libraryName: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    var retryKey by remember { mutableIntStateOf(0) }
    var showRetry by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(retryKey) {
        navigateFromHome(
            awaitResumed = { awaitGenuinelyResumed(lifecycle) },
            viewModel = viewModel,
        ) { dest ->
            showRetry = false
            when (dest) {
                is HomeViewModel.StartDestination.AddSource -> onNavigateToAddSource()
                is HomeViewModel.StartDestination.Library -> {
                    onNavigateToLibrary(dest.sourceType, dest.libraryId, dest.libraryName)
                }
                is HomeViewModel.StartDestination.NoLibraries -> showRetry = true
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        if (showRetry) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_unable_to_connect_to_source),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { retryKey++ }) {
                    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_retry))
                }
            }
        } else {
            CircularProgressIndicator()
        }
    }
}

// navigateAsRoot(library_route) does popUpTo(HOME) { inclusive=false } followed by
// navigate(library_route). The popUpTo step momentarily promotes HOME to RESUMED before the
// subsequent navigate() pushes library_items back on top (demoting HOME to STARTED). If
// lifecycle.withResumed { } is used directly, it fires during that transient RESUMED window,
// waking up navigateFromHome and causing a second navigateAsRoot call → flash of HOME spinner.
//
// awaitGenuinelyResumed yields once after withResumed to let the synchronous navigate() call
// complete, then checks the lifecycle state. If HOME fell back to STARTED the loop repeats and
// we wait for the next RESUMED event — which only arrives when HOME is genuinely the foreground.
internal suspend fun awaitGenuinelyResumed(lifecycle: Lifecycle) {
    awaitGenuinelyResumedWith(
        waitForResumed = { lifecycle.withResumed { } },
        isStillResumed = { lifecycle.currentState >= Lifecycle.State.RESUMED },
    )
}

// Separated so tests can simulate the lifecycle transitions without a real Lifecycle object
// and the Dispatchers.Main dependency that lifecycle.withResumed requires.
internal suspend fun awaitGenuinelyResumedWith(
    waitForResumed: suspend () -> Unit,
    isStillResumed: () -> Boolean,
) {
    while (true) {
        waitForResumed()
        yield()
        if (isStillResumed()) break
    }
}

// Compose Navigation 2.8+ keeps the previous back-stack entry in composition simultaneously
// for its own predictive-back animations. HOME can therefore be in STARTED state while
// library_items is the foreground destination. awaitResumed (backed by awaitGenuinelyResumed in
// production) suspends until HOME is genuinely the foreground destination. In tests it is a
// plain suspend lambda so the test controls exactly when navigation is unblocked.
internal suspend fun navigateFromHome(
    awaitResumed: suspend () -> Unit,
    viewModel: HomeViewModel,
    onDestination: suspend (HomeViewModel.StartDestination) -> Unit,
) {
    awaitResumed()
    val dest = viewModel.getStartDestination()
    withContext(viewModel.dispatchers.mainImmediate) {
        onDestination(dest)
    }
}
