package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelStore
import java.io.File
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * On-disk [PanelStore] — one JSON file per book, holding a list of [PagePanels]. Reads and writes
 * are file-level; concurrent writes for different books are safe, but concurrent writes for the
 * same book race and the last writer wins. That's fine: within one reader session all writes go
 * through a single orchestrator, and cross-session races would just recompute a page whose result
 * is deterministic anyway.
 *
 * File layout: `<rootDir>/<bookId-safe>.json` where `<bookId-safe>` is the `bookId` with any
 * character outside `[A-Za-z0-9._-]` replaced by `_`. The file itself carries the original
 * `bookId` so a collision on the safe filename doesn't produce a wrong load — we sanity-check
 * on read.
 */
class JsonPanelStore @Inject constructor(
    private val rootDir: File,
) : PanelStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    init {
        rootDir.mkdirs()
    }

    override fun load(bookId: String, pageIndex: Int): PagePanels? =
        loadAll(bookId)[pageIndex]

    override fun loadAll(bookId: String): Map<Int, PagePanels> {
        val file = fileFor(bookId)
        if (!file.exists()) return emptyMap()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyMap()
        val doc = runCatching { json.decodeFromString(BookFile.serializer(), text) }.getOrNull()
            ?: return emptyMap()
        // Any mismatch (wrong bookId → filename collision, older schema version → detector change)
        // is treated as a miss so we re-detect on the next open. Older schema versions get
        // silently overwritten when save/saveAll writes the current version back.
        if (doc.bookId != bookId || doc.schemaVersion != CURRENT_SCHEMA_VERSION) return emptyMap()
        return doc.pages.associateBy { it.pageIndex }
    }

    override fun save(bookId: String, page: PagePanels) {
        val existing = loadAll(bookId).toMutableMap()
        existing[page.pageIndex] = page
        writeBook(bookId, existing.values.sortedBy { it.pageIndex })
    }

    override fun saveAll(bookId: String, pages: Collection<PagePanels>) {
        val existing = loadAll(bookId).toMutableMap()
        for (page in pages) existing[page.pageIndex] = page
        writeBook(bookId, existing.values.sortedBy { it.pageIndex })
    }

    override fun clear(bookId: String) {
        fileFor(bookId).delete()
    }

    private fun writeBook(bookId: String, pages: List<PagePanels>) {
        val doc = BookFile(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            bookId = bookId,
            pages = pages,
        )
        val tmp = File(rootDir, "${safe(bookId)}.json.tmp")
        tmp.writeText(json.encodeToString(BookFile.serializer(), doc), Charsets.UTF_8)
        if (!tmp.renameTo(fileFor(bookId))) {
            // Rename can fail across some FUSE filesystems; fall back to direct write.
            fileFor(bookId).writeText(json.encodeToString(BookFile.serializer(), doc), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun fileFor(bookId: String): File = File(rootDir, "${safe(bookId)}.json")

    private fun safe(bookId: String): String = bookId.replace(UNSAFE, "_")

    @Serializable
    private data class BookFile(
        // Missing on files written before the field was added (v1) — Serializable defaults it to
        // 1, so those pre-versioning caches read back as v1 and mismatch the current version
        // (currently 2, bumped when the detector algorithm changes materially).
        val schemaVersion: Int = 1,
        val bookId: String,
        val pages: List<PagePanels>,
    )

    companion object {
        /**
         * Bump when the detector output changes materially (algorithm, coordinate space, panel
         * geometry). Files written with a different version are treated as a cache miss.
         *
         * History:
         *  1 — original single-pass value-based binarize + auto-invert (first landed panel view).
         *  2 — two-pass content-vs-background classifier; auto-invert removed. Files written under
         *      v1 held Fallback results for dark-gutter comics that the v2 detector handles.
         */
        internal const val CURRENT_SCHEMA_VERSION: Int = 2

        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
        internal val PagesListSerializer = ListSerializer(PagePanels.serializer())
    }
}
