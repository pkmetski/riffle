package com.riffle.core.database

import androidx.room.Room
import androidx.sqlite.driver.NativeSQLiteDriver
import kotlinx.coroutines.Dispatchers

fun openRiffleDatabase(path: String): RiffleDatabaseAccess {
    val db = Room.databaseBuilder<RiffleDatabase>(name = path)
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
