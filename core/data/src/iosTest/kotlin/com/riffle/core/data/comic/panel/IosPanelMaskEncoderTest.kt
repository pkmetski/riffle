package com.riffle.core.data.comic.panel

import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.PanelDetectionConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real iOS panel-mask path (CoreGraphics encode, and decode → binarize → encode in
 * [IosPanelMaskServiceImpl]) — the iOS counterpart to Android's `PanelMaskEncoder` + reporting
 * pipeline (ADR 0062). The pure pixel-mapping half is pinned once for both platforms in
 * core:domain's commonTest `PanelMaskPixelsTest`; what needs iOS-specific coverage is the
 * CoreGraphics bitmap/PNG round-trip, which only runs on device/simulator.
 */
class IosPanelMaskEncoderTest {

    /** 4x2 mask: row 0 gutter (white), row 1 content (black). */
    private fun twoRowMask() = PanelBinaryMask(
        width = 4,
        height = 2,
        data = ByteArray(8) { idx -> if (idx / 4 == 1) 1 else 0 },
    )

    @Test
    fun `encode emits a real PNG signature`() {
        val png = IosPanelMaskEncoder.encode(twoRowMask())
        assertNotNull(png)
        assertTrue(png.size > 8, "expected non-trivial PNG payload, got ${png.size} bytes")
        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        val magic = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertTrue(
            png.copyOfRange(0, 8).contentEquals(magic),
            "expected PNG magic bytes, got ${png.copyOfRange(0, 8).joinToString { it.toString() }}",
        )
    }

    @Test
    fun `encoded PNG decodes back to the mask dimensions`() {
        val mask = twoRowMask()
        val png = IosPanelMaskEncoder.encode(mask)!!

        val decoded = IosPageImageDecoder().decode(png)

        assertNotNull(decoded)
        assertEquals(mask.width, decoded.originalWidth)
        assertEquals(mask.height, decoded.originalHeight)
    }

    @Test
    fun `encoded PNG preserves content as black and gutter as white`() {
        val mask = twoRowMask()
        val png = IosPanelMaskEncoder.encode(mask)!!

        val grid = IosPageImageDecoder().decode(png)!!.grid

        // Row 0 was gutter -> white (luma 255); row 1 was content -> black (luma 0).
        for (x in 0 until mask.width) {
            assertEquals(255, grid.get(x, 0), "gutter pixel ($x,0) should be white")
            assertEquals(0, grid.get(x, 1), "content pixel ($x,1) should be black")
        }
    }

    @Test
    fun `encode returns null for a zero-sized mask`() {
        assertNull(IosPanelMaskEncoder.encode(PanelBinaryMask(width = 0, height = 0, data = ByteArray(0))))
    }

    @Test
    fun `generateMask runs the full decode binarize encode pipeline`() = runTest {
        // A synthetic "page": a dark content block on a light background, encoded as a real PNG so
        // the service has to decode it exactly as it would a CBZ page entry.
        val pageWidth = 40
        val pageHeight = 40
        val page = PanelBinaryMask(
            width = pageWidth,
            height = pageHeight,
            data = ByteArray(pageWidth * pageHeight) { idx ->
                val y = idx / pageWidth
                val x = idx % pageWidth
                if (y in 8..31 && x in 8..31) 1 else 0
            },
        )
        val pagePng = IosPanelMaskEncoder.encode(page)!!

        val service = IosPanelMaskServiceImpl(PanelDetectionConfig(), IosPageImageDecoder(), IosDispatcherProvider)
        val result = service.generateMask(pageIndex = 0, rawImageBytes = pagePng)

        assertNotNull(result, "expected a mask for a page with both content and gutter")
        val (mask, maskPng) = result
        assertEquals(pageWidth, mask.width)
        assertEquals(pageHeight, mask.height)
        assertTrue(mask.data.any { it == 1.toByte() }, "expected the dark block to binarize as content")
        assertTrue(mask.data.any { it == 0.toByte() }, "expected the light margin to binarize as gutter")
        assertTrue(maskPng.size > 8, "expected a PNG payload for the report attachment")
    }

    @Test
    fun `generateMask returns null for undecodable bytes`() = runTest {
        val service = IosPanelMaskServiceImpl(PanelDetectionConfig(), IosPageImageDecoder(), IosDispatcherProvider)
        assertNull(service.generateMask(pageIndex = 0, rawImageBytes = ByteArray(0)))
    }
}
