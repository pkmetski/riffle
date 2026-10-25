package com.riffle.shared.reader

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIPasteboard

/**
 * iOS clipboard bridge. Exposed as a singleton object so [IosEpubReaderScreen] can call it without
 * pulling UIKit into a composable directly.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosClipboard {
    fun copyToClipboard(text: String) {
        UIPasteboard.generalPasteboard.string = text
    }
}
