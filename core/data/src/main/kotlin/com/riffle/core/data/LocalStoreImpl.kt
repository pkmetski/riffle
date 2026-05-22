package com.riffle.core.data

import com.riffle.core.domain.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class LocalStoreImpl(private val dir: File, private val extension: String) : LocalStore {

    override fun get(itemId: String): File? =
        dir.resolve("$itemId$extension").takeIf { it.exists() }

    override suspend fun save(itemId: String, stream: InputStream): File =
        withContext(Dispatchers.IO) {
            val dest = dir.resolve("$itemId$extension")
            val tmp = dir.resolve("$itemId$extension.tmp")
            try {
                tmp.outputStream().use { out -> stream.copyTo(out) }
                tmp.renameTo(dest)
                dest
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

    override fun delete(itemId: String) {
        dir.resolve("$itemId$extension").delete()
    }

    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    override fun listItemIds(): List<String> =
        dir.listFiles()
            ?.filter { it.name.endsWith(extension) }
            ?.map { it.name.removeSuffix(extension) }
            ?: emptyList()
}
