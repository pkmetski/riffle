package com.riffle.shared.audiobook

import androidx.compose.runtime.Composable
import com.riffle.core.models.LibraryItem

/**
 * Platform-specific audiobook player screen.
 * iOS actual: [IosAudiobookPlayerScreen] (AVQueuePlayer via bridge).
 * Android: Android uses its own NavGraph in :app and does not need an actual here.
 */
@Composable
expect fun AudiobookPlayerScreen(item: LibraryItem, onBack: () -> Unit)
