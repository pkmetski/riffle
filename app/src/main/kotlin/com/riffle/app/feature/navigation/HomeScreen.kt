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
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onNavigateToAddSource: () -> Unit,
    onNavigateToLibrary: (libraryId: String, libraryName: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    var retryKey by remember { mutableIntStateOf(0) }
    var showRetry by remember { mutableStateOf(false) }

    LaunchedEffect(retryKey) {
        showRetry = false
        val dest = viewModel.getStartDestination()
        // Navigate on the main looper: after getStartDestination() returns through
        // withContext(IO), the Compose test interceptor may resume this continuation
        // on the instrumentation thread. NavController.navigate() calls
        // LifecycleRegistry.setCurrentState() which requires the main thread.
        withContext(viewModel.dispatchers.mainImmediate) {
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
