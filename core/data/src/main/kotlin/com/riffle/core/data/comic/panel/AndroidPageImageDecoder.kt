package com.riffle.core.data.comic.panel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.riffle.core.domain.comic.panel.PageImageDecoder
import com.riffle.core.domain.comic.panel.PixelGrid
import javax.inject.Inject

/**
 * BitmapFactory-backed decoder. Two-pass:
 *  1. `inJustDecodeBounds=true` to read the source dimensions cheaply.
 *  2. Compute the largest `inSampleSize` (power of two) that leaves the long edge ≥ [targetLongEdge].
 *  3. Decode into a [Bitmap], read pixels, convert to greyscale luma using ITU-R BT.601 weights.
 *
 * Bitmaps are recycled immediately after luma extraction; we do not keep them around.
 */
class AndroidPageImageDecoder @Inject constructor() : PageImageDecoder {

    override fun decode(bytes: ByteArray, targetLongEdge: Int): PageImageDecoder.Result? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        val longEdge = maxOf(srcW, srcH)
        var sample = 1
        while (longEdge / (sample * 2) >= targetLongEdge) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val luma = ByteArray(w * h)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // ITU-R BT.601 luma
                val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                luma[i] = y.toByte()
            }
            return PageImageDecoder.Result(
                grid = PixelGrid(w, h, luma),
                originalWidth = srcW,
                originalHeight = srcH,
            )
        } finally {
            bitmap.recycle()
        }
    }
}
