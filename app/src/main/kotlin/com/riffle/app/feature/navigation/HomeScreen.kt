package com.riffle.app.feature.navigation

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onNavigateToAddSource: () -> Unit,
    onNavigateToLibrary: (libraryId: String, libraryName: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    var retryKey by remember { mutableIntStateOf(0) }
    var showRetry by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(retryKey) {
        navigateFromHome(awaitResumed = { lifecycle.withResumed { } }, viewModel = viewModel) { dest ->
            showRetry = false
            when (dest) {
                is HomeViewModel.StartDestination.AddSource -> onNavigateToAddSource()
                is HomeViewModel.StartDestination.Library -> onNavigateToLibrary(dest.libraryId, dest.libraryName)
                is HomeViewModel.StartDestination.NoLibraries -> showRetry = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (showRetry) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Unable to connect to source",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { retryKey++ }) {
                    Text("Retry")
                }
            }
        } else {
            CircularProgressIndicator()
        }
    }
}

// Compose Navigation 2.8+ keeps the previous back-stack entry in composition simultaneously
// for its own predictive-back animations. HOME can therefore be in STARTED state while
// library_items is the foreground destination. Calling navigateAsRoot from STARTED state
// triggers popUpTo(HOME) which removes the live library_items entry, creating a gap with no
// LibraryItemsScreen BackHandler registered. A Back event in that gap falls through to the
// NavHost handler, which pops library_items to HOME, which fires the LaunchedEffect again →
// loop. awaitResumed suspends until HOME is the foreground destination before touching the
// NavController. In production this is `{ lifecycle.withResumed { } }`; in tests it is a
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
