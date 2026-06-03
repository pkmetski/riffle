package com.riffle.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One-time on-disk relocation for the (serverId, itemId) file keying introduced by ADR 0025.
 *
 * Before this change `LocalStore` kept files flat as `dir/<itemId><ext>`; now they live under
 * `dir/<serverId>/<itemId><ext>`. This migrator finds any legacy flat files left at the top level
 * of each store dir, resolves each one's owning Server from the (already-migrated) DB, and moves it
 * into the per-Server subdirectory. Files whose item id no longer resolves to any row are orphans
 * (the book was removed) and are deleted.
 *
 * It is idempotent: once relocation has run there are no top-level files left, so subsequent calls
 * are no-ops — no run-once flag is needed. Resolution depends on the Room 25→26 migration having
 * already backfilled `library_items.serverId`, which it has by the time any DAO query returns.
 */
class LocalStoreMigrator(
    // (store directory, file extension) for each of the four EPUB/PDF cache & downloads stores.
    private val stores: List<Pair<File, String>>,
    private val resolveServerId: suspend (itemId: String) -> String?,
) {
    suspend fun migrate() = withContext(Dispatchers.IO) {
        for ((dir, extension) in stores) {
            val flatFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(extension) } ?: continue
            for (file in flatFiles) {
                val itemId = file.name.removeSuffix(extension)
                val serverId = resolveServerId(itemId)
                if (serverId == null) {
                    // Orphan: no library item owns this id anymore.
                    file.delete()
                    continue
                }
                val destDir = dir.resolve(serverId).also { it.mkdirs() }
                val dest = destDir.resolve(file.name)
                if (!file.renameTo(dest)) {
                    // Rename can fail across links; fall back to copy+delete, then drop the source.
                    runCatching { file.copyTo(dest, overwrite = true) }.onSuccess { file.delete() }
                }
            }
        }
    }
}
