package com.riffle.core.data.localfiles

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class CopyCoverImageUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    operator fun invoke(sourceId: String, sourceItemId: String, contentUriString: String): String? {
        val uri = Uri.parse(contentUriString)
        val dest = File(context.filesDir, "local_covers/${sourceId}_${sourceItemId}.jpg")
        dest.parentFile?.mkdirs()
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.toURI().toString()
        } catch (_: Exception) {
            null
        }
    }
}
