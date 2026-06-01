package com.riffle.core.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

object EpubBundleExtractor {

    fun extractEpub(bundle: InputStream, workingDir: File): File {
        if (!workingDir.exists()) workingDir.mkdirs()
        ZipInputStream(bundle).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".epub", ignoreCase = true)) {
                    val out = File.createTempFile("storyteller-", ".epub", workingDir)
                    out.outputStream().use { zis.copyTo(it) }
                    return out
                }
                entry = zis.nextEntry
            }
        }
        throw IOException("No .epub entry found in Storyteller bundle")
    }
}
