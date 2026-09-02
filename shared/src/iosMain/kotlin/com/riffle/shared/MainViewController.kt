package com.riffle.shared

import androidx.compose.ui.window.ComposeUIViewController

// Called from Swift as MainViewControllerKt.MainViewController() — uppercase name is intentional.
@Suppress("ktlint:standard:function-naming")
fun MainViewController() = ComposeUIViewController {
    LibraryBrowsingApp()
}
