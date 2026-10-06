package com.riffle.shared.reader

import androidx.compose.runtime.Composable
import com.riffle.core.models.LibraryItem
import com.riffle.feature.reader.highlights.ReaderSource

/**
 * Platform-specific EPUB reader screen.
 * iOS actual: [IosEpubReaderScreen] (Readium Swift via UIKitViewController).
 * Android: Android uses its own NavGraph in :app and does not need an actual here.
 *
 * [source] controls whether the full book or the elided Annotations View is shown.
 */
@Composable
expect fun EpubReaderScreen(
    item: LibraryItem,
    onBack: () -> Unit,
    source: ReaderSource = ReaderSource.FullBook,
)
