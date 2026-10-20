@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.riffle.core.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosRiffleDatabasePathTest {

    private var db: RiffleDatabaseAccess? = null
    private var dbName: String = ""

    @BeforeTest
    fun setup() {
        dbName = "ios-db-path-test-${NSUUID().UUIDString}.db"
    }

    @AfterTest
    fun cleanup() {
        db?.close()
        db = null
        if (dbName.isNotEmpty()) deleteRiffleDatabase(dbName)
    }

    @Test
    fun bareNameResolvesToApplicationSupport() = runTest {
        db = openRiffleDatabase(dbName)
        // Execute a query to force Room to open the SQLite connection and create the file on disk.
        db!!.sourceDao().getById("__probe__")

        val appSupport = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: ""
        assertTrue(appSupport.isNotEmpty())
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath("$appSupport/$dbName"),
            "Database should be created at Library/Application Support/$dbName, not at container root",
        )
    }

    @Test
    fun bareNameNotCreatedAtContainerRoot() = runTest {
        db = openRiffleDatabase(dbName)
        db!!.sourceDao().getById("__probe__")

        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath("${NSHomeDirectory()}/$dbName"),
            "Database must not be placed at the container root — Room FileLock returns EPERM there on device",
        )
    }

    @Test
    fun migratesExistingDatabaseFromHomeDirectory() = runTest {
        val oldPath = "${NSHomeDirectory()}/$dbName"
        val appSupport = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: ""
        val newPath = "$appSupport/$dbName"

        // Simulate an existing install: place an empty file at the old container-root location.
        // Room treats an empty SQLite file as a fresh database and recreates it.
        NSFileManager.defaultManager.createFileAtPath(oldPath, contents = null, attributes = null)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(oldPath))

        db = openRiffleDatabase(dbName)
        db!!.sourceDao().getById("__probe__")

        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(newPath),
            "Database should have been migrated to $newPath",
        )
        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(oldPath),
            "Old container-root database should have been moved away",
        )
    }
}
