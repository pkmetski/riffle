package com.riffle.core.database

import androidx.room.RoomDatabaseConstructor

// Room KMP requires an `actual` for every target even when Room is not used on that target.
// On iOS the database is driven by NativeSqliteDriver; this actual is never called.
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object RiffleDatabaseConstructor : RoomDatabaseConstructor<RiffleDatabase> {
    override fun initialize(): RiffleDatabase = error("Room not used on iOS")
}
