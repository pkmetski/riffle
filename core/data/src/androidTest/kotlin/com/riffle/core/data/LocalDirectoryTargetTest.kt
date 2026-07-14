package com.riffle.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for [LocalDirectoryTarget] file I/O operations.
 *
 * Verifies reading, writing, listing, and error handling for annotation files
 * stored in the app's internal files directory.
 */
@RunWith(AndroidJUnit4::class)
class LocalDirectoryTargetTest {

    private lateinit var target: LocalDirectoryTarget
    private lateinit var filesDir: File

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        target = LocalDirectoryTarget(context)
        filesDir = context.filesDir

        // Clean up any previous test data
        val annotationSyncDir = File(filesDir, "annotation-sync")
        if (annotationSyncDir.exists()) {
            annotationSyncDir.deleteRecursively()
        }
    }

    /**
     * Write JSON content and read it back, verifying the content matches exactly.
     *
     * This is the happy path tracer bullet: write → read → equals.
     */
    @Test
    fun writeAndRead_returnsExactContent() = runTest {
        val serverId = "server1"
        val itemId = "item1"
        val filename = "annotations-device1.jsonld"
        val content = """{"id":"ann1","type":"highlight","color":"yellow"}"""

        target.write(serverId, itemId, filename, content)
        val readContent = target.read(serverId, itemId, filename)

        assertEquals(content, readContent)
    }

    /**
     * Write multiple files and verify list() returns all .jsonld filenames.
     *
     * Only .jsonld files should be returned; other files should be filtered out.
     */
    @Test
    fun listFiles_returnsAllJsonldFilenames() = runTest {
        val serverId = "server2"
        val itemId = "item2"
        val filename1 = "annotations-device1.jsonld"
        val filename2 = "annotations-device2.jsonld"
        val filename3 = "annotations-device3.jsonld"

        target.write(serverId, itemId, filename1, """{"data":"1"}""")
        target.write(serverId, itemId, filename2, """{"data":"2"}""")
        target.write(serverId, itemId, filename3, """{"data":"3"}""")

        val files = target.list(serverId, itemId)

        assertEquals(3, files.size)
        assertTrue(files.contains(filename1))
        assertTrue(files.contains(filename2))
        assertTrue(files.contains(filename3))
    }

    /**
     * Verify that list() returns an empty list when the directory does not exist.
     *
     * This should not throw an exception; it's a valid state (no annotations yet).
     */
    @Test
    fun listEmptyDirectory_returnsEmptyList() = runTest {
        val serverId = "server3"
        val itemId = "item3"

        val files = target.list(serverId, itemId)

        assertTrue(files.isEmpty())
    }

    /**
     * Mix .jsonld and other file types; verify only .jsonld files are returned.
     *
     * Tests the filtering logic that excludes non-.jsonld files.
     */
    @Test
    fun listFiles_filtersOutNonJsonldFiles() = runTest {
        val serverId = "server4"
        val itemId = "item4"
        val jsonldFile = "annotations-device1.jsonld"
        val otherFile = "metadata.json"

        target.write(serverId, itemId, jsonldFile, """{"data":"1"}""")
        // Manually write a non-.jsonld file to the directory
        val directory = File(filesDir, "annotation-sync/$serverId/$itemId")
        File(directory, otherFile).writeText("""{"other":"data"}""")

        val files = target.list(serverId, itemId)

        assertEquals(1, files.size)
        assertEquals(jsonldFile, files[0])
        assertFalse(files.contains(otherFile))
    }

    /**
     * Attempt to read a nonexistent file; verify it throws an exception with
     * a clear error message.
     *
     * The read method returns null if the file doesn't exist, but this test
     * verifies the behavior. Actually, per the code, read() returns null,
     * not an exception. We test that null is returned.
     */
    @Test
    fun readNonexistentFile_returnsNull() = runTest {
        val serverId = "server5"
        val itemId = "item5"
        val filename = "nonexistent.jsonld"

        val result = target.read(serverId, itemId, filename)

        assertTrue(result == null)
    }

    /**
     * Write a file, overwrite it with different content, and verify the
     * second write is what gets read back.
     *
     * Tests that write() is idempotent and overwrites existing files.
     */
    @Test
    fun overwriteFile_returnsLatestContent() = runTest {
        val serverId = "server6"
        val itemId = "item6"
        val filename = "annotations-device1.jsonld"
        val v1 = """{"version":"1"}"""
        val v2 = """{"version":"2","updated":true}"""

        target.write(serverId, itemId, filename, v1)
        val readV1 = target.read(serverId, itemId, filename)
        assertEquals(v1, readV1)

        target.write(serverId, itemId, filename, v2)
        val readV2 = target.read(serverId, itemId, filename)
        assertEquals(v2, readV2)
    }

    /**
     * Write files to different server/item combinations and verify they
     * are stored separately without interference.
     *
     * Tests that the directory hierarchy (serverId/itemId) isolates data correctly.
     */
    @Test
    fun multipleDirectories_filesAreIsolated() = runTest {
        val filename = "annotations-device1.jsonld"
        val content1 = """{"server":"server7","item":"item7"}"""
        val content2 = """{"server":"server7","item":"item8"}"""
        val content3 = """{"server":"server8","item":"item7"}"""

        target.write("server7", "item7", filename, content1)
        target.write("server7", "item8", filename, content2)
        target.write("server8", "item7", filename, content3)

        assertEquals(content1, target.read("server7", "item7", filename))
        assertEquals(content2, target.read("server7", "item8", filename))
        assertEquals(content3, target.read("server8", "item7", filename))
    }

    /**
     * Two writes to the same file in quick succession; the second should
     * overwrite the first (concurrent writes).
     *
     * Tests that the second write fully replaces the first.
     */
    @Test
    fun concurrentWrites_latestWinnerPersists() = runTest {
        val serverId = "server9"
        val itemId = "item9"
        val filename = "annotations-device1.jsonld"
        val content1 = """{"seq":1}"""
        val content2 = """{"seq":2}"""

        target.write(serverId, itemId, filename, content1)
        target.write(serverId, itemId, filename, content2)

        val result = target.read(serverId, itemId, filename)
        assertEquals(content2, result)
    }

    /**
     * Write and read content with special characters (JSON-safe) to ensure
     * no encoding issues.
     *
     * Tests that special characters in JSON (quotes, escapes, unicode) round-trip.
     */
    @Test
    fun writeAndRead_preservesSpecialCharacters() = runTest {
        val serverId = "server10"
        val itemId = "item10"
        val filename = "annotations-device1.jsonld"
        val content = """{"text":"He said \"hello\" and\nwent to café. 你好.","emoji":"🎉"}"""

        target.write(serverId, itemId, filename, content)
        val readContent = target.read(serverId, itemId, filename)

        assertEquals(content, readContent)
    }

    /**
     * Write to a deeply nested directory structure and verify it creates
     * all intermediate directories.
     *
     * Tests that mkdirs() is called and succeeds for multi-level hierarchies.
     */
    @Test
    fun writeToDeepPath_createsIntermediateDirectories() = runTest {
        val serverId = "server/with/slashes"
        val itemId = "item/with/slashes"
        val filename = "annotations-device1.jsonld"
        val content = """{"created":true}"""

        target.write(serverId, itemId, filename, content)
        val readContent = target.read(serverId, itemId, filename)

        assertEquals(content, readContent)
    }

    /**
     * Write to different items under the same server and verify list()
     * returns only the files for the queried item.
     *
     * Tests that list() is scoped by both serverId and itemId.
     */
    @Test
    fun listScopedByServerAndItem_doesNotCrossPollinate() = runTest {
        val serverId = "server11"
        val file1 = "annotations-device1.jsonld"
        val file2 = "annotations-device2.jsonld"

        target.write(serverId, "item11a", file1, """{"item":"11a"}""")
        target.write(serverId, "item11b", file2, """{"item":"11b"}""")

        val filesItem11a = target.list(serverId, "item11a")
        val filesItem11b = target.list(serverId, "item11b")

        assertEquals(1, filesItem11a.size)
        assertEquals(file1, filesItem11a[0])
        assertEquals(1, filesItem11b.size)
        assertEquals(file2, filesItem11b[0])
    }

    /**
     * Legacy pre-`abs_` files (bare-UUID namespace dirs) must be renamed in place the first
     * time the target touches the annotation-sync root. After migration, a fresh
     * `list("abs_<uuid>", …)` call finds the legacy content under the new namespace path.
     *
     * Would fail if reverted: the assertion below expects the abs_-prefixed directory to
     * exist and the legacy directory to be gone.
     */
    @Test
    fun listMigratesLegacyBareUuidNamespaceDirs() = runTest {
        val absUuid = "19621aae-1111-2222-3333-4a4a4a4a4a4a"
        val itemId = "book1"
        val filename = "annotations-device-legacy.jsonld"

        // Seed the disk in the pre-`abs_` layout — a bare-UUID namespace dir under the root.
        val root = File(filesDir, "annotation-sync")
        val legacyBookDir = File(root, "$absUuid/$itemId")
        assertTrue(legacyBookDir.mkdirs())
        File(legacyBookDir, filename).writeText("""{"legacy":"content"}""")

        // Fresh target instance so `legacyAbsMigrated` starts false; then hit list().
        val fresh = LocalDirectoryTarget(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val listed = fresh.list("abs_$absUuid", itemId)

        assertEquals(listOf(filename), listed)
        assertTrue("abs_-prefixed dir must exist after migration",
            File(root, "abs_$absUuid").isDirectory)
        assertFalse("bare-UUID dir must be gone after migration",
            File(root, absUuid).exists())
        assertEquals(
            """{"legacy":"content"}""",
            fresh.read("abs_$absUuid", itemId, filename),
        )
    }
}
