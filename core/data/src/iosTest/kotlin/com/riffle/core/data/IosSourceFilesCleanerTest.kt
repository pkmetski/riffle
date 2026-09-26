package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.IosDispatcherProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Removing a source on iOS must take its archives with it (#1101): every per-source directory
 * in the EPUB/PDF/CBZ download and cache stores and the audiobook root, and nothing belonging
 * to another source.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSourceFilesCleanerTest {

    private val root = NSTemporaryDirectory() + "files_cleaner_test_" + NSUUID().UUIDString()

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    private val fileStore = object : FileStore {
        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            NSFileManager.defaultManager.createDirectoryAtPath(base, withIntermediateDirectories = true, attributes = null, error = null)
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }
    }

    private fun write(namespace: String, sourceId: String, name: String): String {
        val path = fileStore.resolve(namespace, "$sourceId/$name")
        IosItemFiles.writeBytes(path, byteArrayOf(1, 2, 3))
        return path
    }

    @Test
    fun deleteAllForSourcePurgesEveryStoreForThatSourceOnly() = runTest {
        val mine = IosSourceFilesCleaner.NAMESPACES.map { write(it, "src-1", "item.bin") }
        val theirs = IosSourceFilesCleaner.NAMESPACES.map { write(it, "src-2", "item.bin") }
        val audiobookNested = write(NS_AUDIOBOOK_DOWNLOADS, "src-1", "book/manifest.json")

        IosSourceFilesCleaner(fileStore, IosDispatcherProvider).deleteAllForSource("src-1")

        (mine + audiobookNested).forEach { assertFalse(IosItemFiles.exists(it), "purged: $it") }
        theirs.forEach { assertTrue(IosItemFiles.exists(it), "untouched: $it") }
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$root/$NS_CBZ_CACHE/src-1"), "the source directory itself is gone")
    }

    @Test
    fun deleteAllForSourceIsANoOpForAnUnknownSource() = runTest {
        val theirs = write(NS_CBZ_DOWNLOADS, "src-2", "item.cbz")

        IosSourceFilesCleaner(fileStore, IosDispatcherProvider).deleteAllForSource("never-existed")

        assertTrue(IosItemFiles.exists(theirs))
    }
}
