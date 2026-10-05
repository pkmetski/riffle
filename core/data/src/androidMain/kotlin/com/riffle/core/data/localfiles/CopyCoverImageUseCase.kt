package com.riffle.core.data.localfiles

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CopyCoverImageUseCase constructor(
    private val context: Context,
) {
    suspend operator fun invoke(sourceId: String, sourceItemId: String, contentUriString: String): String? =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(contentUriString)
            val dest = File(context.filesDir, "local_covers/${sourceId}_${sourceItemId}.jpg")
            dest.parentFile?.mkdirs()
            try {
                val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                stream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.toURI().toString()
            } catch (_: Exception) {
                null
            }
        }
}
