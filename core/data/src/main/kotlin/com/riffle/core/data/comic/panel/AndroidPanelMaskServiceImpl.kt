package com.riffle.core.data.comic.panel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.PanelDetectionConfig
import com.riffle.core.domain.comic.panel.PanelMaskBinarizer
import com.riffle.core.domain.comic.panel.PanelMaskService
import com.riffle.core.domain.comic.panel.PixelGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AndroidPanelMaskServiceImpl @Inject constructor(
    private val config: PanelDetectionConfig,
) : PanelMaskService {

    override suspend fun generateMask(
        pageIndex: Int,
        rawImageBytes: ByteArray,
    ): Pair<PanelBinaryMask, ByteArray>? = withContext(Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.size, opts)
            ?: return@withContext null
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        val luma = ByteArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
        }
        val grid = PixelGrid(w, h, luma)
        val mask = PanelMaskBinarizer(config).binarize(grid) ?: return@withContext null
        mask to PanelMaskEncoder.encode(mask)
    }
}
