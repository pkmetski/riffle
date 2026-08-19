package com.riffle.core.domain.comic.panel

/**
 * Turns raw page-image bytes (JPEG/PNG/etc from a CBZ entry) into a greyscale [PixelGrid] plus
 * the original image's dimensions. The Android implementation uses BitmapFactory — kept as an
 * interface so the detector and orchestrator stay JVM-testable with hand-crafted fixtures.
 *
 * The target long-edge is set high enough that typical comic pages (up to ~8 000 px on the long
 * edge) decode at inSampleSize=1 (full resolution). Running at full resolution keeps the JVM
 * test fixtures (binarized masks at the source image's native resolution) identical to what the
 * detector actually receives on device, so any test that passes in JVM is guaranteed to pass on
 * device. Previous targets (~1 000 px) caused inSampleSize=2 for standard comics, which halved
 * gutter widths, produced border-contaminated gutter rows after binarization, and made
 * device-scale behaviour impossible to reproduce faithfully in mask-based JVM tests.
 */
interface PageImageDecoder {
    data class Result(
        val grid: PixelGrid,
        val originalWidth: Int,
        val originalHeight: Int,
    )

    /**
     * @param bytes the full page-image bytes.
     * @param targetLongEdge the target long-edge dimension for [Result.grid]; the actual grid
     *   is the largest inSampleSize-decoded size that leaves the long edge ≥ this value.
     *   Default is 4 096, which keeps inSampleSize=1 (full resolution) for typical comic
     *   pages (≤ ~8 190 px on the long edge).
     */
    fun decode(bytes: ByteArray, targetLongEdge: Int = 4096): Result?
}
