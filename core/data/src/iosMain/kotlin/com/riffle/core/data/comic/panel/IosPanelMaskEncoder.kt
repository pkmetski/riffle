package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.panelMaskToArgbPixels
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy
import platform.posix.uint8_tVar

/**
 * CoreGraphics-backed PNG encoder for [PanelBinaryMask] — the iOS counterpart to Android's
 * `PanelMaskEncoder`. Shares the ARGB pixel mapping via [panelMaskToArgbPixels] (ADR 0062) so the
 * two platforms can never disagree on which pixels are content vs. gutter; only the
 * pixels-to-PNG-bytes step is platform-specific.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosPanelMaskEncoder {

    fun encode(mask: PanelBinaryMask): ByteArray? {
        val width = mask.width
        val height = mask.height
        if (width <= 0 || height <= 0) return null
        val pixels = panelMaskToArgbPixels(mask)

        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            data = null,
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = (width * 4).toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        )
        CGColorSpaceRelease(colorSpace)
        if (context == null) return null

        return try {
            val raw = CGBitmapContextGetData(context)?.reinterpret<uint8_tVar>() ?: return null
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val base = i * 4
                raw[base] = ((pixel shr 16) and 0xFF).toUByte()
                raw[base + 1] = ((pixel shr 8) and 0xFF).toUByte()
                raw[base + 2] = (pixel and 0xFF).toUByte()
                raw[base + 3] = 0u
            }
            val cgImage = CGBitmapContextCreateImage(context) ?: return null
            try {
                val pngData = UIImagePNGRepresentation(UIImage.imageWithCGImage(cgImage)) ?: return null
                ByteArray(pngData.length.toInt()).also { out ->
                    if (out.isNotEmpty()) {
                        out.usePinned { pinned -> memcpy(pinned.addressOf(0), pngData.bytes, pngData.length) }
                    }
                }
            } finally {
                CGImageRelease(cgImage)
            }
        } finally {
            CGContextRelease(context)
        }
    }
}
