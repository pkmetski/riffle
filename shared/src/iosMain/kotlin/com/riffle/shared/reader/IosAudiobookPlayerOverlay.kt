package com.riffle.shared.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.riffle.core.models.LibraryItem
import com.riffle.shared.audiobook.AudiobookPlayerScreen

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun IosAudiobookPlayerOverlay(
    item: LibraryItem,
    visible: Boolean,
    onDismiss: () -> Unit,
    onSwitchToReadaloud: (ebookItemId: String, atSec: Double) -> Unit,
) {
    val slideProgress = remember { Animatable(0f) }
    var overlayVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            overlayVisible = true
            slideProgress.snapTo(0f)
            slideProgress.animateTo(1f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
        } else {
            slideProgress.animateTo(0f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
            overlayVisible = false
        }
    }
    if (!overlayVisible) return
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            translationY = (1f - slideProgress.value) * size.height
        },
    ) {
        AudiobookPlayerScreen(
            item = item,
            playlistId = null,
            playlistLibraryId = null,
            startAtSec = -2.0,
            onBack = onDismiss,
            onPlaylistAdvance = { _, _ -> },
            onSwitchToReadaloud = onSwitchToReadaloud,
        )
    }
}
