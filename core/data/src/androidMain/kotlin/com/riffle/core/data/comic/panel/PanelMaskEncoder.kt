package com.riffle.core.data.comic.panel

import android.graphics.Bitmap
import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.panelMaskToArgbPixels
import java.io.ByteArrayOutputStream

/**
 * Encodes a [PanelBinaryMask] as a lossless PNG. Content pixels (1) → black;
 * gutter pixels (0) → white. The result is copyright-safe and loadable directly
 * by PanelDetectorTest fixtures (ADR 0062).
 */
object PanelMaskEncoder {

    /** Exposed for unit testing — converts mask to ARGB pixel array without Bitmap. */
    fun toArgbPixels(mask: PanelBinaryMask): IntArray = panelMaskToArgbPixels(mask)

    fun encode(mask: PanelBinaryMask): ByteArray {
        val pixels = toArgbPixels(mask)
        val bitmap = Bitmap.createBitmap(pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
