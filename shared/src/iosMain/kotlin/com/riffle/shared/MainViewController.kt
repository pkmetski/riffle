package com.riffle.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import coil3.compose.setSingletonImageLoaderFactory
import com.riffle.feature.source.ui.RiffleTheme

// Called from Swift as MainViewControllerKt.MainViewController() — uppercase name is intentional.
@Suppress("ktlint:standard:function-naming")
fun MainViewController() = ComposeUIViewController {
    // Registers Coil's Ktor network fetcher — see [iosImageLoader]. Must happen before the first
    // remote image request, so it sits at the root of the composition.
    setSingletonImageLoaderFactory { context -> iosImageLoader(context) }
    // RiffleTheme provides MaterialTheme (colours + typography) to the full iOS composition,
    // fixing the "no theme" gap vs Android. safeDrawingPadding keeps content inside the safe
    // area on devices with a notch, home indicator, and rounded corners (fixes C1).
    RiffleTheme {
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            LibraryBrowsingApp()
        }
    }
}
