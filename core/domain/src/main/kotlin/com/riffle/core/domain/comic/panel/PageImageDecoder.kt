package com.riffle.core.domain.comic.panel

/**
 * Turns raw page-image bytes (JPEG/PNG/etc from a CBZ entry) into a downscaled greyscale
 * [PixelGrid] plus the original image's dimensions. The Android implementation uses
 * BitmapFactory with `inSampleSize` for the downscale — kept as an interface here so the
 * detector and orchestrator stay JVM-testable with hand-crafted fixtures.
 */
interface PageImageDecoder {
    data class Result(
        val grid: PixelGrid,
        val originalWidth: Int,
        val originalHeight: Int,
    )

    /**
     * @param bytes the full page-image bytes.
     * @param targetLongEdge the target long-edge dimension for [Result.grid]; the actual grid may
     *   be somewhat smaller (inSampleSize halves) or somewhat larger, but should be in the same
     *   order of magnitude.
     */
    fun decode(bytes: ByteArray, targetLongEdge: Int = 1000): Result?
}
