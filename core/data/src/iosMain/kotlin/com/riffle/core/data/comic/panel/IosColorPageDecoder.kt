package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.ColorPageDecoder
import com.riffle.core.domain.comic.panel.ColorPageImage
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
 * CoreGraphics-backed [ColorPageDecoder] for iOS. Decodes the page bytes via [UIImage], then draws
 * the [platform.CoreGraphics.CGImage] into a downsampled RGBA bitmap context and reads the pixels
 * back as packed ARGB ints, matching the layout the shared seam sampler expects.
 *
 * The `sample` loop mirrors the Android decoder so the downsampled dimensions are comparable; the
 * seam math ([com.riffle.core.domain.comic.panel.comicSeamEnergySampler]) is identical across
 * platforms. Panel View / SMART_SPLIT is not yet wired on iOS (the panel engine is a no-op there),
 * but this decoder makes the seam pipeline platform-complete.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosColorPageDecoder : ColorPageDecoder {

    override fun decode(bytes: ByteArray, targetLongEdge: Int): ColorPageImage? {
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
        val bytesPerRow = dstW * 4
        val context = CGBitmapContextCreate(
            data = null,
            width = dstW.toULong(),
            height = dstH.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = bytesPerRow.toULong(),
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
            val argb = IntArray(dstW * dstH) { i ->
                val base = i * 4
                // UByte.toInt() is already in 0..255 (no sign extension), so no mask is needed.
                val r = raw[base].toInt()
                val g = raw[base + 1].toInt()
                val b = raw[base + 2].toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return ColorPageImage(width = dstW, height = dstH, argb = argb)
        } finally {
            CGContextRelease(context)
        }
    }
}
