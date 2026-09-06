package com.riffle.core.domain.comic.panel

import kotlin.math.sqrt

/**
 * Pure seam-energy scan for SMART_SPLIT (ADR 0055 Panel View). Builds the energy-sampler lambda
 * that [PanelOverflowTransform.applyOverflow] consumes to find the lowest-energy seam when a panel
 * overflows the viewport.
 *
 * Previously this lived inline in the Android `CbzReaderViewModel` and read pixels straight off an
 * `android.graphics.Bitmap`. It now operates on a platform-neutral [ColorPageImage] so the exact
 * same math runs on Android and iOS (and in JVM/iOS unit tests). The gradient computation is
 * unchanged: per-channel RGB differences between adjacent pixels along the split axis, summed as an
 * L2 magnitude — identical to the historical `columnEnergies`/`rowEnergies`.
 *
 * [imageWidth]/[imageHeight] are the *original* (full-resolution) page dimensions in the same
 * coordinate space as the [PanelRegion]s; [image] is the downsampled decode. The sampler scales
 * panel coordinates into the downsampled image before scanning.
 */
fun comicSeamEnergySampler(
    image: ColorPageImage,
    imageWidth: Int,
    imageHeight: Int,
): (panel: PanelRegion, splitHorizontally: Boolean) -> FloatArray {
    val scaleX = image.width.toFloat() / imageWidth
    val scaleY = image.height.toFloat() / imageHeight
    // The split axis is decided by PanelOverflowTransform (max zoom gain), not here — the sampler
    // just computes energies along whichever axis the transform asks for.
    return { panel, splitHorizontally ->
        if (splitHorizontally) columnEnergies(image, panel, scaleX, scaleY)
        else rowEnergies(image, panel, scaleX, scaleY)
    }
}

private fun columnEnergies(
    image: ColorPageImage,
    panel: PanelRegion,
    scaleX: Float,
    scaleY: Float,
): FloatArray {
    val bmpX = (panel.x * scaleX).toInt().coerceIn(0, image.width - 1)
    val bmpW = (panel.width * scaleX).toInt().coerceAtLeast(1).coerceAtMost(image.width - bmpX)
    val bmpY = (panel.y * scaleY).toInt().coerceIn(0, image.height - 1)
    val bmpH = (panel.height * scaleY).toInt().coerceAtLeast(1).coerceAtMost(image.height - bmpY)
    if (bmpW == 0 || bmpH < 2) return FloatArray(bmpW)
    return FloatArray(bmpW) { col ->
        var energy = 0f
        val x = bmpX + col
        for (row in 0 until bmpH - 1) {
            val i1 = (bmpY + row) * image.width + x
            val i2 = (bmpY + row + 1) * image.width + x
            val dr = image.red(i1) - image.red(i2)
            val dg = image.green(i1) - image.green(i2)
            val db = image.blue(i1) - image.blue(i2)
            energy += sqrt((dr * dr + dg * dg + db * db).toFloat())
        }
        energy
    }
}

private fun rowEnergies(
    image: ColorPageImage,
    panel: PanelRegion,
    scaleX: Float,
    scaleY: Float,
): FloatArray {
    val bmpX = (panel.x * scaleX).toInt().coerceIn(0, image.width - 1)
    val bmpW = (panel.width * scaleX).toInt().coerceAtLeast(1).coerceAtMost(image.width - bmpX)
    val bmpY = (panel.y * scaleY).toInt().coerceIn(0, image.height - 1)
    val bmpH = (panel.height * scaleY).toInt().coerceAtLeast(1).coerceAtMost(image.height - bmpY)
    if (bmpH == 0 || bmpW < 2) return FloatArray(bmpH)
    return FloatArray(bmpH) { row ->
        var energy = 0f
        val y = bmpY + row
        for (col in 0 until bmpW - 1) {
            val i1 = y * image.width + (bmpX + col)
            val i2 = y * image.width + (bmpX + col + 1)
            val dr = image.red(i1) - image.red(i2)
            val dg = image.green(i1) - image.green(i2)
            val db = image.blue(i1) - image.blue(i2)
            energy += sqrt((dr * dr + dg * dg + db * db).toFloat())
        }
        energy
    }
}
