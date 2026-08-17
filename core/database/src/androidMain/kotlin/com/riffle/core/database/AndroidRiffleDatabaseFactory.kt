package com.riffle.core.database

import android.content.Context
import androidx.room.Room

fun openRiffleDatabase(
    context: Context,
    name: String = "riffle.db",
    allowMainThreadQueries: Boolean = false,
    fallbackToDestructiveMigration: Boolean = false,
): RiffleDatabaseAccess =
    Room.databaseBuilder<RiffleDatabase>(
        context = context.applicationContext,
        name = name,
    ).apply {
        if (allowMainThreadQueries) allowMainThreadQueries()
        if (fallbackToDestructiveMigration) {
            fallbackToDestructiveMigration(dropAllTables = true)
        }
    }.buildRiffleDatabase()

fun openInMemoryRiffleDatabase(
    context: Context,
    allowMainThreadQueries: Boolean = false,
): RiffleDatabaseAccess =
    Room.inMemoryDatabaseBuilder<RiffleDatabase>(context.applicationContext)
        .apply {
            if (allowMainThreadQueries) allowMainThreadQueries()
        }
        .buildRiffleDatabase()

fun RiffleDatabaseAccess.clearAllTables() {
    require(this is DefaultRiffleDatabaseAccess) {
        "clearAllTables is only supported for databases opened by core:database"
    }
    database.clearAllTables()
}
