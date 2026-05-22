package com.riffle.app.feature.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onNavigateToAddServer: () -> Unit,
    onNavigateToLibrary: (libraryId: String, libraryName: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        val dest = viewModel.getStartDestination()
        // Navigate on the main looper: after getStartDestination() returns through
        // withContext(IO), the Compose test interceptor may resume this continuation
        // on the instrumentation thread. NavController.navigate() calls
        // LifecycleRegistry.setCurrentState() which requires the main thread.
        withContext(Dispatchers.Main.immediate) {
            when (dest) {
                is HomeViewModel.StartDestination.AddServer -> onNavigateToAddServer()
                is HomeViewModel.StartDestination.Library -> onNavigateToLibrary(dest.libraryId, dest.libraryName)
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize())
}
