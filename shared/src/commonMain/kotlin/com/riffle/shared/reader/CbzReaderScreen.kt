package com.riffle.shared.reader

import androidx.compose.runtime.Composable
import com.riffle.core.models.LibraryItem

/**
 * Platform-specific CBZ/comics reader screen.
 * iOS actual: [IosCbzReaderScreen].
 * Android: Android uses its own NavGraph in :app and does not need an actual here.
 */
@Composable
expect fun CbzReaderScreen(item: LibraryItem, onBack: () -> Unit)
