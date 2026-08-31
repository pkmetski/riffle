package com.riffle.core.database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

internal fun RoomDatabase.Builder<RiffleDatabase>.buildRiffleDatabase(): RiffleDatabaseAccess =
    addMigrations(*RIFFLE_DATABASE_MIGRATIONS)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
        .let { DefaultRiffleDatabaseAccess(it) }
