package com.riffle.core.domain.comic.panel

private const val ARGB_WHITE = 0xFFFFFFFF.toInt()
private const val ARGB_BLACK = 0xFF000000.toInt()

/**
 * Maps a [PanelBinaryMask] to an ARGB_8888 pixel array — content pixels (1) → black,
 * gutter pixels (0) → white — shared by the platform-specific PNG encoders
 * (`PanelMaskEncoder` on Android, `IosPanelMaskEncoder` on iOS) so the pixel semantics
 * can't drift between platforms (ADR 0062).
 */
fun panelMaskToArgbPixels(mask: PanelBinaryMask): IntArray {
    val pixels = IntArray(mask.width * mask.height)
    for (i in pixels.indices) {
        pixels[i] = if (mask.data[i] == 1.toByte()) ARGB_BLACK else ARGB_WHITE
    }
    return pixels
}
