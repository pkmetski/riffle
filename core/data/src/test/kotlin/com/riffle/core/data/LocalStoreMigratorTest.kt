package com.riffle.core.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalStoreMigratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeFlat(dir: File, name: String, content: String): File =
        dir.resolve(name).apply { parentFile?.mkdirs(); writeText(content) }

    @Test
    fun migrate_relocatesFlatFilesUnderOwningServer() = runTest {
        val epubDir = tmp.newFolder("epubs")
        writeFlat(epubDir, "1.epub", "A's book one")
        writeFlat(epubDir, "2.epub", "A's book two")

        // itemId "1" belongs to server A, "2" to server B.
        val owners = mapOf("1" to "serverA", "2" to "serverB")
        val migrator = LocalStoreMigrator(
            stores = listOf(epubDir to ".epub"),
            resolveServerId = { owners[it] },
        )

        migrator.migrate()

        assertFalse("flat file should be gone", epubDir.resolve("1.epub").exists())
        assertEquals("A's book one", epubDir.resolve("serverA").resolve("1.epub").readText())
        assertEquals("A's book two", epubDir.resolve("serverB").resolve("2.epub").readText())
    }

    @Test
    fun migrate_deletesOrphansWithNoOwningServer() = runTest {
        val epubDir = tmp.newFolder("epubs")
        writeFlat(epubDir, "ghost.epub", "no owner")

        val migrator = LocalStoreMigrator(
            stores = listOf(epubDir to ".epub"),
            resolveServerId = { null },
        )

        migrator.migrate()

        assertFalse("orphan should be deleted", epubDir.resolve("ghost.epub").exists())
        assertTrue("no server subdir created", epubDir.listFiles()?.none { it.isDirectory } ?: true)
    }

    @Test
    fun migrate_isIdempotent_leavesRelocatedFilesUntouched() = runTest {
        val epubDir = tmp.newFolder("epubs")
        writeFlat(epubDir, "1.epub", "content")
        val migrator = LocalStoreMigrator(
            stores = listOf(epubDir to ".epub"),
            resolveServerId = { "serverA" },
        )

        migrator.migrate()
        // Second run: nothing flat remains, so the already-relocated file is left in place.
        migrator.migrate()

        assertEquals("content", epubDir.resolve("serverA").resolve("1.epub").readText())
    }

    @Test
    fun migrate_ignoresFilesAlreadyInServerSubdirs() = runTest {
        val epubDir = tmp.newFolder("epubs")
        // A file already correctly placed under a server dir must not be touched / re-resolved.
        writeFlat(epubDir.resolve("serverA"), "1.epub", "already placed")

        var resolverCalls = 0
        val migrator = LocalStoreMigrator(
            stores = listOf(epubDir to ".epub"),
            resolveServerId = { resolverCalls++; "serverB" },
        )

        migrator.migrate()

        assertEquals("resolver should not run for subdir files", 0, resolverCalls)
        assertEquals("already placed", epubDir.resolve("serverA").resolve("1.epub").readText())
    }
}
