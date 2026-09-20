package com.riffle.app.feature.source.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView

/**
 * The Android half of [com.riffle.feature.source.ui.SourceBrowseHeader]'s `drawerButtonModifier`
 * seam: declares a system gesture exclusion rect over the hamburger button so a quick tap near
 * the left edge is never mis-captured as a back-swipe, and clears it on dispose.
 *
 * This is the only part of the header that could not move into `feature:source-ui` — it needs
 * `LocalView` and `android.graphics.Rect`, which an android+iOS source set cannot reference.
 * iOS has no equivalent concept and passes the default `Modifier`.
 */
@Composable
fun rememberDrawerButtonGestureExclusion(): Modifier {
    val view = LocalView.current
    DisposableEffect(view) {
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                view.systemGestureExclusionRects = emptyList()
            }
        }
    }
    return Modifier.onGloballyPositioned { coords ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val bounds = coords.boundsInWindow()
            view.systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()),
            )
        }
    }
}
