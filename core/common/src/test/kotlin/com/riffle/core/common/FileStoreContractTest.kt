package com.riffle.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Contract test for [FileStore] using a temp-dir-backed implementation.
 * Verifies the resolve() semantics that all platform implementations must honour.
 */
class FileStoreContractTest {

    @get:Rule val tmp = TemporaryFolder()

    private val store: FileStore by lazy {
        TempDirFileStore(tmp.root)
    }

    @Test
    fun `resolve namespace root creates directory and returns absolute path`() {
        val path = store.resolve("my-namespace")

        val dir = File(path)
        assertTrue("namespace root directory must exist after resolve", dir.isDirectory)
        assertTrue("returned path must be absolute", dir.isAbsolute)
        assertTrue("namespace dir must be under root", path.startsWith(tmp.root.absolutePath))
    }

    @Test
    fun `resolve with relativePath returns path under namespace`() {
        val path = store.resolve("ns", "subdir/file.json")

        assertTrue("returned path must be absolute", File(path).isAbsolute)
        assertTrue("file path must contain namespace segment", path.contains("ns"))
        assertTrue("file path must contain relative segment", path.endsWith("subdir/file.json"))
    }

    @Test
    fun `resolve same namespace twice returns same root`() {
        val first = store.resolve("stable")
        val second = store.resolve("stable")

        assertEquals("same namespace must resolve to the same path", first, second)
    }

    @Test
    fun `different namespaces resolve to different directories`() {
        val a = store.resolve("alpha")
        val b = store.resolve("beta")

        assertTrue("distinct namespaces must not share a root directory", a != b)
    }

    /** Minimal [FileStore] backed by a temp dir — usable for tests in pure-JVM modules. */
    private class TempDirFileStore(private val root: File) : FileStore {
        override fun resolve(namespace: String, relativePath: String): String {
            val base = File(root, namespace)
            return if (relativePath.isEmpty()) {
                base.apply { mkdirs() }.absolutePath
            } else {
                File(base, relativePath).also { it.parentFile?.mkdirs() }.absolutePath
            }
        }
    }
}
