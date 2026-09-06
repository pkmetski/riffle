package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PageImageDecoder
import com.riffle.core.domain.comic.panel.PixelGrid
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.posix.uint8_tVar

/**
 * CoreGraphics-backed [PageImageDecoder] for iOS — the greyscale counterpart to
 * [IosColorPageDecoder], producing the luma [PixelGrid] the panel detector consumes. Mirrors
 * `AndroidPageImageDecoder`: two-pass down-sample (power-of-two long-edge target), draw into an
 * RGBA context, then convert to ITU-R BT.601 luma. Replacing the iOS no-op panel engine with a
 * real [com.riffle.core.domain.comic.panel.PanelOrchestrator] uses this decoder.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPageImageDecoder : PageImageDecoder {

    override fun decode(bytes: ByteArray, targetLongEdge: Int): PageImageDecoder.Result? {
        if (bytes.isEmpty()) return null
        val nsData: NSData = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val cgImage = UIImage.imageWithData(nsData)?.CGImage ?: return null

        val srcW = CGImageGetWidth(cgImage).toInt()
        val srcH = CGImageGetHeight(cgImage).toInt()
        if (srcW <= 0 || srcH <= 0) return null

        var sample = 1
        while (maxOf(srcW, srcH) / (sample * 2) >= targetLongEdge) sample *= 2

        val dstW = (srcW / sample).coerceAtLeast(1)
        val dstH = (srcH / sample).coerceAtLeast(1)

        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            data = null,
            width = dstW.toULong(),
            height = dstH.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = (dstW * 4).toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        )
        CGColorSpaceRelease(colorSpace)
        if (context == null) return null

        try {
            CGContextDrawImage(
                context,
                CGRectMake(0.0, 0.0, dstW.toDouble(), dstH.toDouble()),
                cgImage,
            )
            val raw = CGBitmapContextGetData(context)?.reinterpret<uint8_tVar>() ?: return null
            val luma = ByteArray(dstW * dstH) { i ->
                val base = i * 4
                val r = raw[base].toInt()
                val g = raw[base + 1].toInt()
                val b = raw[base + 2].toInt()
                // ITU-R BT.601 luma, matching AndroidPageImageDecoder.
                val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                y.toByte()
            }
            return PageImageDecoder.Result(
                grid = PixelGrid(dstW, dstH, luma),
                originalWidth = srcW,
                originalHeight = srcH,
            )
        } finally {
            CGContextRelease(context)
        }
    }
}
