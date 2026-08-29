package com.riffle.core.domain.comic.panel

/**
 * Generates a sanitised binary mask from a raw page image for use in panel detection
 * failure reports. The mask discards all art and retains only the gutter structure
 * (ADR 0062).
 *
 * Separated from [PanelEngine] because mask generation is report-specific and
 * uses platform image APIs ([android.graphics.BitmapFactory] on Android).
 */
interface PanelMaskService {

    /**
     * Decode [rawImageBytes], binarize the page, and return the [PanelBinaryMask]
     * together with its PNG-encoded bytes. Returns null if the page is a uniform
     * solid (binarizer returns null) or on I/O error.
     *
     * Must be called off the main thread.
     */
    suspend fun generateMask(pageIndex: Int, rawImageBytes: ByteArray): Pair<PanelBinaryMask, ByteArray>?
}
