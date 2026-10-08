package com.riffle.core.database

import androidx.room.Room
import androidx.sqlite.driver.NativeSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * Resolves [name] (a bare filename like "riffle.db") to an absolute path inside the app's
 * Documents directory. Room KMP with NativeSQLiteDriver does not accept bare filenames — it
 * requires a full path, unlike SQLDelight's NativeSqliteDriver which resolved the location
 * automatically. The Documents directory is guaranteed to exist on every app container.
 *
 * If [name] is already absolute (starts with '/'), it is returned unchanged so tests that
 * construct their own paths (e.g. in a temp directory) still work.
 */
@OptIn(ExperimentalForeignApi::class)
private fun resolveDbPath(name: String): String {
    if (name.startsWith('/')) return name
    val docs = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )?.path ?: error("Could not locate NSDocumentDirectory")
    return "$docs/$name"
}

fun openRiffleDatabase(path: String): RiffleDatabaseAccess {
    val db = Room.databaseBuilder<RiffleDatabase>(name = resolveDbPath(path))
        .addMigrations(RiffleDatabase.MIGRATION_IOS_SQLDELIGHT_6_75)
        // Devices that were on SQLDelight schema versions 1–5 (very old iOS installs, pre-v4
        // of the iOS app) have no reading positions / audiobook positions / formatting prefs
        // tables. Those rows can't be preserved through the witness migration, so fall back to
        // a destructive recreate — users lose only local preference overrides (positions are
        // server-synced and will be restored on the next library refresh).
        .fallbackToDestructiveMigrationFrom(dropAllTables = false, 1, 2, 3, 4, 5)
        .setDriver(NativeSQLiteDriver())
        // Kotlin/Native exposes no separate IO dispatcher; Default is the background pool.
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
    return DefaultRiffleDatabaseAccess(db)
}

fun deleteRiffleDatabase(name: String) {
    // Room on iOS stores the database in the application's documents directory.
    // Deletion is handled by the OS file system via NSFileManager in Swift when needed;
    // this stub is retained for call-site compatibility.
}
