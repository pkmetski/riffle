package com.riffle.app.feature.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun HomeScreen(
    onNavigateToAddServer: () -> Unit,
    onNavigateToLibrary: (libraryId: String, libraryName: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        when (val dest = viewModel.getStartDestination()) {
            is HomeViewModel.StartDestination.AddServer -> onNavigateToAddServer()
            is HomeViewModel.StartDestination.Library -> onNavigateToLibrary(dest.libraryId, dest.libraryName)
        }
    }
    Box(modifier = Modifier.fillMaxSize())
}
