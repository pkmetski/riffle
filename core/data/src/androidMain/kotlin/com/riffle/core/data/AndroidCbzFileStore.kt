package com.riffle.core.data

import com.riffle.core.common.FileStore
import java.io.File

internal class AndroidCbzFileStore(
    private val downloadsDir: File,
    private val cacheDir: File,
) : FileStore {

    override fun resolve(namespace: String, relativePath: String): String {
        val root = rootFor(namespace)
        return if (relativePath.isEmpty()) {
            root.absolutePath
        } else {
            File(root, relativePath).absolutePath
        }
    }

    override fun exists(namespace: String, relativePath: String): Boolean =
        File(resolve(namespace, relativePath)).exists()

    private fun rootFor(namespace: String): File = when (namespace) {
        NS_CBZ_DOWNLOADS -> downloadsDir
        NS_CBZ_CACHE -> cacheDir
        else -> error("AndroidCbzFileStore: unsupported namespace '$namespace'")
    }
}
