package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * On-disk [PanelStore] for iOS — the iOS counterpart to Android's `JsonPanelStore`.
 *
 * One JSON file per book, holding a list of [PagePanels]. File layout:
 * `<rootDir>/<bookId-safe>.json` where `<bookId-safe>` replaces any character outside
 * `[A-Za-z0-9._-]` with `_`. The file carries the original `bookId` so collisions are caught
 * on read. Panel results survive app restarts, eliminating the per-session redetection that
 * `InMemoryPanelStore` requires.
 *
 * Concurrent writes for the same book race with last-writer-wins — acceptable because within
 * a reader session all writes flow through a single orchestrator and detection is deterministic.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosJsonPanelStore(private val rootDir: String) : PanelStore {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(
            rootDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    override fun load(bookId: String, pageIndex: Int): PagePanels? =
        loadAll(bookId)[pageIndex]

    override fun loadAll(bookId: String): Map<Int, PagePanels> {
        val path = filePath(bookId)
        val text = readText(path) ?: return emptyMap()
        val doc = runCatching { json.decodeFromString(BookFile.serializer(), text) }.getOrNull() ?: return emptyMap()
        if (doc.bookId != bookId) return emptyMap()
        return doc.pages.associateBy { it.pageIndex }
    }

    override fun save(bookId: String, page: PagePanels) {
        val existing = loadAll(bookId).toMutableMap()
        existing[page.pageIndex] = page
        writeAll(bookId, existing.values.toList())
    }

    override fun saveAll(bookId: String, pages: Collection<PagePanels>) {
        val existing = loadAll(bookId).toMutableMap()
        pages.forEach { existing[it.pageIndex] = it }
        writeAll(bookId, existing.values.toList())
    }

    override fun clear(bookId: String) {
        val path = filePath(bookId)
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    private fun writeAll(bookId: String, pages: List<PagePanels>) {
        val doc = BookFile(bookId = bookId, pages = pages)
        val text = runCatching { json.encodeToString(BookFile.serializer(), doc) }.getOrNull() ?: return
        writeText(filePath(bookId), text)
    }

    private fun filePath(bookId: String): String {
        val safe = bookId.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")
        return "$rootDir/$safe.json"
    }

    private fun readText(path: String): String? {
        val data = NSData.create(contentsOfFile = path) ?: return null
        val size = data.length.toInt()
        if (size == 0) return ""
        val bytes = ByteArray(size)
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return bytes.decodeToString()
    }

    private fun writeText(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        if (bytes.isEmpty()) {
            NSData().writeToFile(path, atomically = true)
            return
        }
        bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                .writeToFile(path, atomically = true)
        }
    }

    @Serializable
    private data class BookFile(val bookId: String, val pages: List<PagePanels>)
}
