package com.riffle.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Translucent black used for the nav-bar scrim and the reader TopAppBar. ~60% alpha keeps
// content faintly visible while giving system icons enough contrast to stay legible.
fun bottomBarScrimColor(): Color = Color.Black.copy(alpha = 0.6f)

// Paints a translucent strip under the OS navigation bar inset. Necessary because Android
// forces navigationBarColor transparent on gesture-nav devices regardless of what we pass
// to enableEdgeToEdge — without this scrim the gesture pill / nav buttons sit on bare app
// content. Height tracks WindowInsets.navigationBars, so when bars are hidden (e.g. reader
// immersive mode) the inset collapses to 0 and the scrim disappears automatically.
@Composable
fun BottomNavBarScrim(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
            .background(bottomBarScrimColor()),
    )
}
