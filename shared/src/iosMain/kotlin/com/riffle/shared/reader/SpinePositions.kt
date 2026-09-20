package com.riffle.shared.reader

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSNumber
import platform.Foundation.create

/**
 * The publication's reading order plus the Readium position count of each resource — iOS's
 * counterpart of Android's `publication.readingOrder` + `publication.positionsByReadingOrder()`
 * pair in `EpubReaderViewModel.spinePositionCounts`.
 *
 * Both lists are index-aligned. Either may be empty while Readium is still computing positions.
 */
internal data class SpinePositions(
    val hrefs: List<String>,
    val positionCounts: List<Int>,
) {
    /** A spine that cannot weight the rail yet. */
    val isUsable: Boolean get() = hrefs.isNotEmpty() && positionCounts.isNotEmpty()

    companion object {
        val Empty = SpinePositions(emptyList(), emptyList())
    }
}

/**
 * Parse `{"hrefs":["ch1.xhtml",…],"positionCounts":[12,…]}` as produced by
 * [IosEpubNavigatorBridge.getSpineJson].
 *
 * Tolerant by design: malformed or partial JSON yields [SpinePositions.Empty] rather than
 * throwing, because the reader polls this while the publication is still opening. The two lists
 * are truncated to their common length — an index-misaligned pair would silently mis-weight every
 * rail segment, which is far worse than falling back to unweighted segments.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun parseSpineJson(json: String): SpinePositions {
    val bytes = json.encodeToByteArray()
    if (bytes.isEmpty()) return SpinePositions.Empty
    val data = bytes.usePinned { p ->
        NSData.create(bytes = p.addressOf(0), length = bytes.size.toULong())
    }
    val root = NSJSONSerialization.JSONObjectWithData(data = data, options = 0u, error = null)
        as? NSDictionary ?: return SpinePositions.Empty
    val hrefsArray = root.objectForKey("hrefs") as? NSArray ?: return SpinePositions.Empty
    val countsArray = root.objectForKey("positionCounts") as? NSArray ?: return SpinePositions.Empty

    val hrefs = ArrayList<String>(hrefsArray.count.toInt())
    for (i in 0 until hrefsArray.count.toLong()) {
        hrefs += hrefsArray.objectAtIndex(i.toULong()) as? String ?: return SpinePositions.Empty
    }
    val counts = ArrayList<Int>(countsArray.count.toInt())
    for (i in 0 until countsArray.count.toLong()) {
        counts += (countsArray.objectAtIndex(i.toULong()) as? NSNumber)?.intValue ?: 0
    }
    val common = minOf(hrefs.size, counts.size)
    if (common == 0) return SpinePositions.Empty
    return SpinePositions(hrefs.take(common), counts.take(common))
}
