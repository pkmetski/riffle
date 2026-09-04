package com.riffle.shared.reader

import androidx.compose.runtime.Composable
import com.riffle.core.models.LibraryItem

/**
 * Platform-specific EPUB reader screen.
 * iOS actual: [IosEpubReaderScreen] (Readium Swift via UIKitViewController).
 * Android: Android uses its own NavGraph in :app and does not need an actual here.
 */
@Composable
expect fun EpubReaderScreen(item: LibraryItem, onBack: () -> Unit)
