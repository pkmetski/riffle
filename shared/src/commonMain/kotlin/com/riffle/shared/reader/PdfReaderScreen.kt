package com.riffle.shared.reader

import androidx.compose.runtime.Composable
import com.riffle.core.models.LibraryItem

/**
 * Platform-specific PDF reader screen.
 * iOS actual: [IosPdfReaderScreen] (PDFKit via UIKitViewController).
 * Android uses its own NavGraph in :app and does not need an actual here.
 */
@Composable
expect fun PdfReaderScreen(item: LibraryItem, onBack: () -> Unit)
