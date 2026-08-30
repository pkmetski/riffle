package com.riffle.core.catalog

/**
 * Implemented by Sources that contain live-stream items (e.g. radio stations).
 * Live-stream items suppress the download button and progress bar.
 */
interface LiveStreamCapability : CatalogCapability {
    fun isLiveStream(itemId: String): Boolean
}
