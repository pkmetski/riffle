package com.riffle.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.riffle.feature.library.HomeViewModel
import org.koin.compose.koinInject

@Composable
fun HomeScreen() {
    val viewModel = koinInject<HomeViewModel>()
    var destination by remember { mutableStateOf<HomeViewModel.StartDestination?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        destination = viewModel.getStartDestination()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val dest = destination) {
            null -> BasicText("Loading…")
            is HomeViewModel.StartDestination.AddSource -> AddAbsSourceScreen(
                onSourceAdded = { refreshKey++ },
            )
            is HomeViewModel.StartDestination.NoLibraries -> BasicText("No libraries found")
            is HomeViewModel.StartDestination.Library -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("Library: ${dest.libraryName}")
            }
        }
    }
}
