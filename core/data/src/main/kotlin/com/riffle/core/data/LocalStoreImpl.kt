package com.riffle.core.data

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.StoredItemRef
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

// Files live under dir/<sourceId>/<itemId><ext> so item ids that collide across Servers
// (e.g. two Storyteller Services each emitting "1") never overwrite each other (ADR 0025).
class LocalStoreImpl(
    private val dir: File,
    private val extension: String,
    private val dispatchers: DispatcherProvider,
) : LocalStore {

    private fun fileFor(sourceId: String, itemId: String): File =
        dir.resolve(sourceId).resolve("$itemId$extension")

    override fun get(sourceId: String, itemId: String): File? =
        fileFor(sourceId, itemId).takeIf { it.exists() }

    override suspend fun save(sourceId: String, itemId: String, stream: InputStream): File =
        withContext(dispatchers.io) {
            val serverDir = dir.resolve(sourceId).also { it.mkdirs() }
            val dest = serverDir.resolve("$itemId$extension")
            val tmp = serverDir.resolve("$itemId$extension.tmp")
            // Item ids may contain '/' (e.g. Chitanka's "book/12018-…", "prikazki/…"), which lands
            // dest/tmp inside a nested subdirectory of serverDir. Create it before opening the stream,
            // otherwise the tmp write fails ENOENT ("No such file or directory").
            tmp.parentFile?.mkdirs()
            try {
                tmp.outputStream().use { out -> stream.copyTo(out) }
                tmp.renameTo(dest)
                dest
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

    override fun delete(sourceId: String, itemId: String) {
        fileFor(sourceId, itemId).delete()
    }

    override fun deleteSource(sourceId: String) {
        dir.resolve(sourceId).deleteRecursively()
    }

    override fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    override fun listItems(): List<StoredItemRef> =
        dir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { serverDir ->
                // Chitanka item ids contain '/' (e.g. "book/12018-…"), which puts saved files inside
                // nested subdirectories of serverDir. Walk the whole tree so those items list, then
                // rebuild the item id from the relative path.
                val prefix = serverDir.absolutePath + File.separator
                serverDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(extension) }
                    .map { file ->
                        val relative = file.absolutePath.removePrefix(prefix)
                        StoredItemRef(
                            sourceId = serverDir.name,
                            itemId = relative.removeSuffix(extension),
                        )
                    }
                    .toList()
            }
            ?: emptyList()
}
