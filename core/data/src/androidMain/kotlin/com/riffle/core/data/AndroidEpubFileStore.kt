package com.riffle.core.data

import com.riffle.core.common.FileStore
import java.io.File

/**
 * [FileStore] for [EpubLocalPathResolver] on Android.
 *
 * Maps [NS_EPUB_DOWNLOADS] and [NS_EPUB_CACHE] to the directories that Android's epub
 * [LocalStoreImpl] instances use. These are rooted differently from [FilesdirFileStore]
 * (downloads live under filesDir, cache under cacheDir), so a dedicated store is required
 * rather than reusing the app-wide [FilesdirFileStore].
 */
internal class AndroidEpubFileStore(
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
        NS_EPUB_DOWNLOADS -> downloadsDir
        NS_EPUB_CACHE -> cacheDir
        else -> error("AndroidEpubFileStore: unsupported namespace '$namespace'")
    }
}
