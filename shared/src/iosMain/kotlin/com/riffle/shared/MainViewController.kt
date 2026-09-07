package com.riffle.shared

import androidx.compose.ui.window.ComposeUIViewController
import coil3.compose.setSingletonImageLoaderFactory

// Called from Swift as MainViewControllerKt.MainViewController() — uppercase name is intentional.
@Suppress("ktlint:standard:function-naming")
fun MainViewController() = ComposeUIViewController {
    // Registers Coil's Ktor network fetcher — see [iosImageLoader]. Must happen before the first
    // remote image request, so it sits at the root of the composition.
    setSingletonImageLoaderFactory { context -> iosImageLoader(context) }
    LibraryBrowsingApp()
}
