package com.riffle.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSHomeDirectory

fun openRiffleDatabase(path: String): RiffleDatabaseAccess {
    // BundledSQLiteDriver packages its own SQLite so the app is not subject to iOS system SQLite's
    // SQLITE_OMIT_LOAD_EXTENSION constraint. Room KMP's official iOS examples use BundledSQLiteDriver.
    //
    // The path must be absolute. SQLDelight's NativeSqliteDriver resolved bare filenames to
    // Library/Application Support/databases/ automatically; Room does not. NSHomeDirectory() gives
    // the app container root; Room writes the database to NSHomeDirectory()/Documents/riffle.db
    // by convention (Documents is the only user-visible directory, so it is the natural home).
    val absolutePath = if (path.startsWith('/')) path else "${NSHomeDirectory()}/$path"
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
    // Room on iOS stores the database in the application's home directory.
    // Deletion is handled by the OS file system via NSFileManager in Swift when needed;
    // this stub is retained for call-site compatibility.
}
