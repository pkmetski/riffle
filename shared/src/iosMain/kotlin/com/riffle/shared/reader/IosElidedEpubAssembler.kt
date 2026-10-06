package com.riffle.shared.reader

import com.riffle.core.models.LibraryItem
import com.riffle.feature.reader.highlights.ChapterElision
import com.riffle.feature.reader.highlights.ElidedEpubPackager
import com.riffle.feature.reader.highlights.renderChapterHtml
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create

/**
 * Writes a synthetic exploded EPUB for the elided Annotations View to a temp directory and
 * returns its absolute path.
 *
 * The directory layout matches [ElidedEpubPackager]:
 * ```
 * <dir>/
 *   mimetype
 *   META-INF/container.xml
 *   content.opf
 *   nav.xhtml
 *   highlights/
 *     ch0.xhtml
 *     ch1.xhtml
 *     ...
 * ```
 *
 * Each chapter XHTML is produced by [renderChapterHtml], byte-identical to Android's
 * [HighlightsPublicationFactory] output. Readium-Swift opens the directory via
 * [IosEpubNavigatorBridge.openSyntheticEpub] with `isDirectory = true`.
 *
 * The directory is keyed by `item.id` inside [NSTemporaryDirectory], so repeated opens of the
 * same book overwrite the same directory without unbounded temp growth.
 *
 * Returns `null` when [chapters] is empty, all chapters have no highlights, or the write fails.
 */
object IosElidedEpubAssembler {

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    fun assemble(item: LibraryItem, chapters: List<ChapterElision>): String? {
        val nonEmpty = chapters.filter { it.highlights.isNotEmpty() }
        if (nonEmpty.isEmpty()) return null

        val fileManager = NSFileManager.defaultManager
        val tempBase = NSTemporaryDirectory()
        val dirPath = "${tempBase}riffle_elided_${item.id}"

        // Remove any stale directory from a previous open.
        fileManager.removeItemAtPath(dirPath, error = null)

        for (subdir in listOf(dirPath, "$dirPath/META-INF", "$dirPath/highlights")) {
            val ok = fileManager.createDirectoryAtPath(
                subdir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            if (!ok) return null
        }

        val files = buildMap<String, String> {
            put("mimetype", ElidedEpubPackager.MIME_TYPE_CONTENT)
            put("META-INF/container.xml", ElidedEpubPackager.buildContainerXml())
            put("content.opf", ElidedEpubPackager.buildOpf(item.title, item.id, nonEmpty))
            put("nav.xhtml", ElidedEpubPackager.buildNav(item.title, nonEmpty))
            nonEmpty.forEachIndexed { i, chapter ->
                put(ElidedEpubPackager.chapterHref(i), renderChapterHtml(chapter))
            }
        }

        for ((relative, content) in files) {
            val bytes = content.encodeToByteArray()
            val nsData = bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
            val wrote = fileManager.createFileAtPath(
                path = "$dirPath/$relative",
                contents = nsData,
                attributes = null,
            )
            if (!wrote) return null
        }

        return dirPath
    }
}
