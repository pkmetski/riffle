package com.riffle.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ServerEntity::class, LibraryEntity::class, LibraryItemEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class RiffleDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun libraryDao(): LibraryDao
    abstract fun libraryItemDao(): LibraryItemDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `libraries` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mediaType` TEXT NOT NULL, `serverId` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_items` (`id` TEXT NOT NULL, `libraryId` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `coverUrl` TEXT, `readingProgress` REAL NOT NULL, `isDownloaded` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `libraries` ADD COLUMN `isUnsupported` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `isSupported` INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
