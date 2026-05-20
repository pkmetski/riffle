package com.riffle.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ServerEntity::class], version = 1, exportSchema = false)
abstract class RiffleDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
}
