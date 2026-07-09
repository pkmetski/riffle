package com.riffle.core.data.localfiles

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * Copies bytes into `filesDir/localfiles/<sourceId>/`. Books land as `<hash>.<ext>`; covers as
 * `covers/<hash>.<ext>`. Partial writes on aborted copies are deleted before returning.
 */
class AndroidCopyInService @Inject constructor(
    @ApplicationContext private val context: Context,
) : CopyInService {

    override suspend fun copyBook(
        sourceId: String,
        sourceItemId: String,
        extension: String,
        stream: InputStream,
    ): File {
        val out = bookFile(sourceId, sourceItemId, extension)
        out.parentFile?.mkdirs()
        try {
            out.outputStream().use { dst -> stream.copyTo(dst) }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return out
    }

    override suspend fun writeCover(
        sourceId: String,
        sourceItemId: String,
        extension: String,
        bytes: ByteArray,
    ): File {
        val out = coverFile(sourceId, sourceItemId, extension)
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        return out
    }

    override suspend fun deleteBook(sourceId: String, sourceItemId: String) {
        val dir = sourceDir(sourceId)
        if (!dir.exists()) return
        dir.listFiles { f -> f.isFile && f.nameWithoutExtension == sourceItemId }
            ?.forEach { it.delete() }
    }

    override suspend fun deleteCover(sourceId: String, sourceItemId: String) {
        val dir = File(sourceDir(sourceId), "covers")
        if (!dir.exists()) return
        dir.listFiles { f -> f.isFile && f.nameWithoutExtension == sourceItemId }
            ?.forEach { it.delete() }
    }

    private fun sourceDir(sourceId: String): File = File(context.filesDir, "localfiles/$sourceId")
    private fun bookFile(sourceId: String, sourceItemId: String, extension: String): File =
        File(sourceDir(sourceId), "$sourceItemId.$extension")
    private fun coverFile(sourceId: String, sourceItemId: String, extension: String): File =
        File(sourceDir(sourceId), "covers/$sourceItemId.$extension")
}
