@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.riffle.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

fun openRiffleDatabase(path: String): RiffleDatabaseAccess {
    // BundledSQLiteDriver packages its own SQLite so the app is not subject to iOS system SQLite's
    // SQLITE_OMIT_LOAD_EXTENSION constraint. Room KMP's official iOS examples use BundledSQLiteDriver.
    //
    // The path must be absolute. Bare filenames are resolved to Library/Application Support/<name>.
    // The app container root (NSHomeDirectory()) does not allow creating files via plain POSIX open()
    // on iOS — Room's FileLock creates a .lck sidecar that way, causing EPERM on launch. The
    // Library/Application Support/ subdirectory is the correct iOS location for app databases and
    // does not have this restriction.
    //
    // Existing installs that have the database at NSHomeDirectory()/<name> (the old location from the
    // initial Room migration) are migrated in-place: the db, wal, and shm files are moved to the
    // new location before Room opens them.
    val absolutePath = if (path.startsWith('/')) {
        path
    } else {
        val appSupport = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: "${NSHomeDirectory()}/Library/Application Support"
        NSFileManager.defaultManager.createDirectoryAtPath(
            appSupport,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val newPath = "$appSupport/$path"
        val oldPath = "${NSHomeDirectory()}/$path"
        if (!NSFileManager.defaultManager.fileExistsAtPath(newPath) &&
            NSFileManager.defaultManager.fileExistsAtPath(oldPath)
        ) {
            for (suffix in listOf("", "-wal", "-shm")) {
                NSFileManager.defaultManager.moveItemAtPath(
                    "$oldPath$suffix",
                    toPath = "$newPath$suffix",
                    error = null,
                )
            }
        }
        newPath
    }
    val db = Room.databaseBuilder<RiffleDatabase>(name = absolutePath)
        .addMigrations(RiffleDatabase.MIGRATION_IOS_SQLDELIGHT_6_75)
        // Devices that were on SQLDelight schema versions 1–5 (very old iOS installs, pre-v4
        // of the iOS app) have no reading positions / audiobook positions / formatting prefs
        // tables. Those rows can't be preserved through the witness migration, so fall back to
        // a destructive recreate — users lose only local preference overrides (positions are
        // server-synced and will be restored on the next library refresh).
        .fallbackToDestructiveMigrationFrom(dropAllTables = false, 1, 2, 3, 4, 5)
        .setDriver(BundledSQLiteDriver())
        // Kotlin/Native exposes no separate IO dispatcher; Default is the background pool.
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
    return DefaultRiffleDatabaseAccess(db)
}

fun deleteRiffleDatabase(name: String) {
    val basePath = if (name.startsWith('/')) {
        name
    } else {
        val appSupport = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: "${NSHomeDirectory()}/Library/Application Support"
        "$appSupport/$name"
    }
    val fm = NSFileManager.defaultManager
    for (suffix in listOf("", "-wal", "-shm", ".lck")) {
        fm.removeItemAtPath("$basePath$suffix", error = null)
    }
}
