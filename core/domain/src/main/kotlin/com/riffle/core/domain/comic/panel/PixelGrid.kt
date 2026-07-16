package com.riffle.core.domain.comic.panel

/**
 * A downscaled greyscale representation of a comic page image, suitable for panel detection.
 *
 * Row-major; each byte is a luminance value in `[0, 255]` (stored as `Byte`, so read via
 * `luma[i].toInt() and 0xFF`). The producer is Android-side (BitmapFactory + inSampleSize +
 * greyscale conversion); this domain-side type is deliberately Bitmap-agnostic so the detector
 * is pure JVM and unit-testable without an emulator.
 *
 * Convention: producers should target ~1000 px on the long edge — small enough for the detector
 * to run in under ~150 ms on a 2018-era mid-range phone, large enough for thin gutter borders
 * to survive downsampling.
 */
class PixelGrid(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "PixelGrid dimensions must be positive" }
        require(luma.size == width * height) {
            "luma size ${luma.size} does not match ${width}x${height}"
        }
    }

    fun get(x: Int, y: Int): Int = luma[y * width + x].toInt() and 0xFF
}
