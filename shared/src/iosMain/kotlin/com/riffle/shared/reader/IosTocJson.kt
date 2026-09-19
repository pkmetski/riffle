package com.riffle.shared.reader

import com.riffle.core.models.TocEntry
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSJSONSerialization
import platform.Foundation.create

/**
 * iOS counterpart of Android's `List<Link>.toTocEntries()`: turns the TOC JSON the Swift Readium
 * bridge hands back into the shared [TocEntry] tree.
 *
 * Extracted from `ReadiumSwiftNavigator` (issue #1066) so the mapping has direct test coverage —
 * `IosTocJsonTest` pins the same four claims as Android's `TocParserTest`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun parseTocJson(json: String): List<TocEntry> {
    val bytes = json.encodeToByteArray()
    val data = bytes.usePinned { p ->
        NSData.create(bytes = p.addressOf(0), length = bytes.size.toULong())
    }
    val array = NSJSONSerialization.JSONObjectWithData(data = data, options = 0u, error = null)
        as? NSArray ?: return emptyList()
    return parseTocArray(array)
}

internal fun parseTocArray(array: NSArray): List<TocEntry> {
    val result = mutableListOf<TocEntry>()
    for (i in 0 until array.count.toLong()) {
        val dict = array.objectAtIndex(i.toULong()) as? NSDictionary ?: continue
        // An entry without an href has nothing to navigate to, so it is skipped — but a missing
        // title is NOT a reason to drop it. Nav documents routinely carry untitled grouping
        // elements whose children are real chapters; dropping the parent took the whole subtree
        // with it, so those chapters vanished from the iOS TOC. Android maps the same case to a
        // blank title (`link.title.orEmpty()`) and lets `flattenToc` skip just the container
        // while still descending into it.
        val href = dict.objectForKey("href") as? String ?: continue
        val title = dict.objectForKey("title") as? String ?: ""
        val childrenArray = dict.objectForKey("children") as? NSArray
        val children = childrenArray?.let { parseTocArray(it) } ?: emptyList()
        result += TocEntry(title = title, href = href, children = children)
    }
    return result
}
