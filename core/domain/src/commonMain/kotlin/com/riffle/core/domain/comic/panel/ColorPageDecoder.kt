package com.riffle.core.domain.comic.panel

/**
 * A downsampled, full-colour representation of a comic page image, suitable for the SMART_SPLIT
 * seam-energy scan (ADR 0055 Panel View). Row-major; each element is a packed ARGB int using the
 * same layout as Android's `Bitmap.getPixels` (`0xAARRGGBB`), so the per-channel accessors below
 * match `android.graphics.Color.red/green/blue`.
 *
 * Deliberately platform-neutral: the producer is a [ColorPageDecoder] actual (Android:
 * `BitmapFactory`; iOS: CoreGraphics), so the seam math in [ComicSeamEnergySampler] stays pure and
 * unit-testable on JVM + iOS without a device.
 *
 * Distinct from [PixelGrid], which is greyscale-only and drives panel *detection*. The seam scan
 * needs colour gradients, so it cannot reuse the luma grid.
 */
class ColorPageImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "ColorPageImage dimensions must be positive" }
        require(argb.size == width * height) {
            "argb size ${argb.size} does not match ${width}x${height}"
        }
    }

    fun red(index: Int): Int = (argb[index] shr 16) and 0xFF
    fun green(index: Int): Int = (argb[index] shr 8) and 0xFF
    fun blue(index: Int): Int = argb[index] and 0xFF
}

/**
 * Turns raw page-image bytes (JPEG/PNG/etc from a CBZ entry) into a downsampled [ColorPageImage].
 * Mirrors [PageImageDecoder] but preserves colour and targets a small long edge (the seam scan only
 * needs a low-resolution image). Kept as an interface — rather than an inline `BitmapFactory` call —
 * so the seam pipeline is shared across Android and iOS and so tests can inject a fake decoder.
 */
interface ColorPageDecoder {
    /**
     * @param bytes the full page-image bytes.
     * @param targetLongEdge the target long-edge dimension; the actual image is the largest
     *   power-of-two-downsampled size that leaves the long edge ≥ this value. Default 300 matches
     *   the historical SMART_SPLIT sampler resolution.
     * @return the decoded colour image, or null when the bytes can't be decoded.
     */
    fun decode(bytes: ByteArray, targetLongEdge: Int = 300): ColorPageImage?
}
