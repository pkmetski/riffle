package com.riffle.app.feature.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Compute the `BitmapFactory.Options.inSampleSize` power-of-two that shrinks a
 * [srcWidth]x[srcHeight] image so both dimensions land at or under [reqWidth] and
 * [reqHeight]. Matches the recipe from the Android developer docs.
 *
 * A raw `decodeByteArray` on a 12-megapixel figure allocates ~48 MB (4 bytes/px). On a
 * 1 GB Android 7.1 tablet that alone can trip an OOM in the annotations panel or the
 * figure-zoom overlay. Downsampling to viewport-scale drops the peak by 8-16x with no
 * visible loss for the panel's thumbnail rendering; the zoom overlay picks the largest
 * dimension the user can actually see (pinch-zoom scales the tile with graphicsLayer,
 * so no source-pixel fidelity is lost inside the fit-to-viewport size).
 *
 * Any non-positive input parameter returns 1 (no downsample) — a defensive floor for
 * cases where the natural dimensions aren't known before decode.
 */
internal fun calculateBitmapSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    reqWidth: Int,
    reqHeight: Int,
): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
    var sample = 1
    var halfW = srcWidth / 2
    var halfH = srcHeight / 2
    while (halfW / sample >= reqWidth && halfH / sample >= reqHeight) {
        sample *= 2
    }
    return sample
}

/**
 * Decode [bytes] into a Bitmap capped to fit within [reqWidth]x[reqHeight] via a
 * bounds-only first pass. Returns null on malformed bytes.
 */
internal fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(
            boundsOpts.outWidth, boundsOpts.outHeight, reqWidth, reqHeight,
        )
        inJustDecodeBounds = false
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
