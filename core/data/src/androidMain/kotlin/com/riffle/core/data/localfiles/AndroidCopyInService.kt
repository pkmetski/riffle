package com.riffle.core.data.localfiles

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Source
import okio.buffer
import okio.sink
import java.io.File

/**
 * Copies bytes into `filesDir/localfiles/<sourceId>/`. Books land as `<hash>.<ext>`; covers as
 * `covers/<hash>.<ext>`. Partial writes on aborted copies are deleted before returning.
 */
class AndroidCopyInService constructor(
    private val context: Context,
) : CopyInService {

    override suspend fun copyBook(
        sourceId: String,
        sourceItemId: String,
        extension: String,
        source: Source,
    ): String = withContext(Dispatchers.IO) {
        val out = bookFile(sourceId, sourceItemId, extension)
        out.parentFile?.mkdirs()
        try {
            source.use { src -> out.sink().buffer().use { dst -> dst.writeAll(src) } }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        out.absolutePath
    }

    override suspend fun writeCover(
        sourceId: String,
        sourceItemId: String,
        extension: String,
        bytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val out = coverFile(sourceId, sourceItemId, extension)
        out.parentFile?.mkdirs()
        try {
            out.writeBytes(bytes)
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        out.absolutePath
    }

    override suspend fun deleteBook(sourceId: String, sourceItemId: String): Unit = withContext(Dispatchers.IO) {
        val dir = sourceDir(sourceId)
        if (!dir.exists()) return@withContext
        dir.listFiles { f -> f.isFile && f.nameWithoutExtension == sourceItemId }
            ?.forEach { it.delete() }
    }

    override suspend fun deleteCover(sourceId: String, sourceItemId: String): Unit = withContext(Dispatchers.IO) {
        val dir = File(sourceDir(sourceId), "covers")
        if (!dir.exists()) return@withContext
        dir.listFiles { f -> f.isFile && f.nameWithoutExtension == sourceItemId }
            ?.forEach { it.delete() }
    }

    private fun sourceDir(sourceId: String): File = File(context.filesDir, "localfiles/$sourceId")
    private fun bookFile(sourceId: String, sourceItemId: String, extension: String): File =
        File(sourceDir(sourceId), "$sourceItemId.$extension")
    private fun coverFile(sourceId: String, sourceItemId: String, extension: String): File =
        File(sourceDir(sourceId), "covers/$sourceItemId.$extension")
}
