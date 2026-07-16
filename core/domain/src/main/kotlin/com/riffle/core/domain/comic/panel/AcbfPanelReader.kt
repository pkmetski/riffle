package com.riffle.core.domain.comic.panel

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Parses panel regions out of an ACBF sidecar. ACBF is XML; each `<page>` contains
 * zero-or-more `<frame points="x1,y1 x2,y2 ..." />` elements whose `points` attribute is a
 * whitespace-separated list of `x,y` pairs describing a polygon. We collapse each polygon to its
 * axis-aligned bounding box (panels in comics are almost always rectangular; the polygon form
 * exists for exotic layouts and is not worth honouring in v1).
 *
 * Standard `ComicInfo.xml` has no panel-region field, so no equivalent reader exists.
 */
class AcbfPanelReader {

    /**
     * @param acbfXml the entire ACBF document as a string.
     * @param pageImageDimensions ordered list of `(width, height)` per page in the archive; needed
     *   because ACBF `<frame points>` are in image pixels, but we still stamp them on
     *   [PagePanels.imageWidth] / `imageHeight` for downstream renderers to reason about.
     * @return one [PagePanels] per page that has at least one `<frame>`. Pages without frames are
     *   omitted; the caller falls through to the auto-detector.
     */
    fun read(acbfXml: String, pageImageDimensions: List<Pair<Int, Int>>): List<PagePanels> {
        if (acbfXml.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(acbfXml, "", Parser.xmlParser()) }.getOrNull()
            ?: return emptyList()
        val pageElements = doc.select("page")
        val result = mutableListOf<PagePanels>()

        for ((index, page) in pageElements.withIndex()) {
            if (index >= pageImageDimensions.size) break
            val (w, h) = pageImageDimensions[index]
            val regions = page.select("frame").mapNotNull { frame ->
                val points = frame.attr("points").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                parsePointsToRegion(points, w, h)
            }
            if (regions.isEmpty()) continue
            result.add(
                PagePanels(
                    pageIndex = index,
                    imageWidth = w,
                    imageHeight = h,
                    panels = regions,
                    source = PanelSource.Acbf,
                ),
            )
        }
        return result
    }

    private fun parsePointsToRegion(points: String, imageWidth: Int, imageHeight: Int): PanelRegion? {
        val coords = points.trim().split(WHITESPACE).mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val x = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val y = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
            x to y
        }
        if (coords.isEmpty()) return null
        val minX = coords.minOf { it.first }.coerceAtLeast(0)
        val minY = coords.minOf { it.second }.coerceAtLeast(0)
        val maxX = coords.maxOf { it.first }.coerceAtMost(imageWidth)
        val maxY = coords.maxOf { it.second }.coerceAtMost(imageHeight)
        val width = (maxX - minX).coerceAtLeast(1)
        val height = (maxY - minY).coerceAtLeast(1)
        return PanelRegion(x = minX, y = minY, width = width, height = height)
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
