package com.riffle.core.data.comic.panel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.riffle.core.domain.comic.panel.ColorPageDecoder
import com.riffle.core.domain.comic.panel.ColorPageImage

/**
 * BitmapFactory-backed [ColorPageDecoder]. Two-pass decode mirroring [AndroidPageImageDecoder]:
 *  1. `inJustDecodeBounds=true` to read the source dimensions cheaply.
 *  2. Compute the largest power-of-two `inSampleSize` that leaves the long edge ≥ [targetLongEdge].
 *  3. Decode into an ARGB_8888 [Bitmap], copy pixels into an IntArray [ColorPageImage].
 *
 * The `inSampleSize` loop is identical to the historical inline SMART_SPLIT sampler, so the decoded
 * dimensions — and therefore the seam energies — are byte-identical to the pre-seam-extraction
 * behaviour. The Bitmap is recycled immediately after the pixel copy; the caller holds only the
 * heap-allocated IntArray, which the GC reclaims normally (no more native-memory recycle dance).
 */
class AndroidColorPageDecoder constructor() : ColorPageDecoder {

    override fun decode(bytes: ByteArray, targetLongEdge: Int): ColorPageImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sample = 1
        while (maxOf(srcW, srcH) / (sample * 2) >= targetLongEdge) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            return ColorPageImage(width = w, height = h, argb = pixels)
        } finally {
            bitmap.recycle()
        }
    }
}
