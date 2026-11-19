package com.riffle.core.data.dictionary

import com.riffle.core.common.FileStore
import java.io.File

internal class AndroidDictFileStore(private val filesDir: File) : FileStore {
    override fun resolve(namespace: String, relativePath: String): String {
        val base = File(filesDir, namespace).also { it.mkdirs() }
        return if (relativePath.isEmpty()) base.absolutePath else File(base, relativePath).absolutePath
    }

    override fun exists(namespace: String, relativePath: String): Boolean =
        File(filesDir, "$namespace/$relativePath").exists()
}
