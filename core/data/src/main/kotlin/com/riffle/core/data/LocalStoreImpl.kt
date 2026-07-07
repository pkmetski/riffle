package com.riffle.core.data

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.StoredItemRef
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

// Files live under dir/<sourceId>/<itemId><ext> so item ids that collide across Servers
// (e.g. two Storyteller Servers each emitting "1") never overwrite each other (ADR 0025).
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

    override fun deleteServer(sourceId: String) {
        dir.resolve(sourceId).deleteRecursively()
    }

    override fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    override fun listItems(): List<StoredItemRef> =
        dir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { serverDir ->
                serverDir.listFiles()
                    ?.filter { it.name.endsWith(extension) }
                    ?.map { StoredItemRef(sourceId = serverDir.name, itemId = it.name.removeSuffix(extension)) }
                    ?: emptyList()
            }
            ?: emptyList()
}
