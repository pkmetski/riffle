package com.riffle.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import coil3.compose.setSingletonImageLoaderFactory

// Called from Swift as MainViewControllerKt.MainViewController() — uppercase name is intentional.
@Suppress("ktlint:standard:function-naming")
fun MainViewController() = ComposeUIViewController {
    // Registers Coil's Ktor network fetcher — see [iosImageLoader]. Must happen before the first
    // remote image request, so it sits at the root of the composition.
    setSingletonImageLoaderFactory { context -> iosImageLoader(context) }
    // RiffleAppRoot provides MaterialTheme (colours + typography) to the full iOS composition,
    // driven by AppearanceCoordinator so the Settings App Theme picker actually applies, and
    // pumps the OS dark flag back into the coordinator. Edge-to-edge: the Surface fills the full
    // screen; individual Scaffolds consume WindowInsets.systemBars so TopAppBar / NavigationBar
    // backgrounds extend behind the status bar and home indicator (no white bars).
    RiffleAppRoot {
        Surface(modifier = Modifier.fillMaxSize()) {
            LibraryBrowsingApp()
        }
    }
}
