package com.riffle.core.database

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

internal fun RoomDatabase.Builder<RiffleDatabase>.buildRiffleDatabase(): RiffleDatabaseAccess =
    addMigrations(*RIFFLE_DATABASE_MIGRATIONS)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
        .asDatabaseAccess()

private fun RiffleDatabase.asDatabaseAccess(): RiffleDatabaseAccess =
    DefaultRiffleDatabaseAccess(this)

internal class DefaultRiffleDatabaseAccess(
    internal val database: RiffleDatabase,
) : RiffleDatabaseAccess {
    override fun close() = database.close()
    override fun sourceDao() = database.sourceDao()
    override fun libraryDao() = database.libraryDao()
    override fun libraryItemDao() = database.libraryItemDao()
    override fun seriesDao() = database.seriesDao()
    override fun collectionDao() = database.collectionDao()
    override fun readingPositionDao() = database.readingPositionDao()
    override fun bookFormattingPreferencesDao() = database.bookFormattingPreferencesDao()
    override fun readaloudLinkDao() = database.readaloudLinkDao()
    override fun readaloudCandidateDao() = database.readaloudCandidateDao()
    override fun readaloudDismissalDao() = database.readaloudDismissalDao()
    override fun crossEpubIndexDao() = database.crossEpubIndexDao()
    override fun annotationDao() = database.annotationDao()
    override fun readaloudResumePositionDao() = database.readaloudResumePositionDao()
    override fun audioPlaybackPreferencesDao() = database.audioPlaybackPreferencesDao()
    override fun audiobookPositionDao() = database.audiobookPositionDao()
    override fun audiobookBookmarkDao() = database.audiobookBookmarkDao()
    override fun tocCacheDao() = database.tocCacheDao()
    override fun audiobookChapterCacheDao() = database.audiobookChapterCacheDao()
    override fun localFilesFolderDao() = database.localFilesFolderDao()
    override fun localFilesFileDao() = database.localFilesFileDao()
    override fun localFilesFileFolderDao() = database.localFilesFileFolderDao()
    override fun localFileMetadataOverrideDao() = database.localFileMetadataOverrideDao()
    override fun remoteItemFreshnessDao() = database.remoteItemFreshnessDao()
    override fun playlistDao() = database.playlistDao()
    override fun publicationMetricsCacheDao() = database.publicationMetricsCacheDao()
    override fun bookComicFormattingPreferencesDao() = database.bookComicFormattingPreferencesDao()
    override fun dictionaryPackDao() = database.dictionaryPackDao()
    override fun lookupHistoryDao() = database.lookupHistoryDao()
}

internal val RIFFLE_DATABASE_MIGRATIONS: Array<Migration> = arrayOf(
    RiffleDatabase.MIGRATION_1_2,
    RiffleDatabase.MIGRATION_2_3,
    RiffleDatabase.MIGRATION_3_4,
    RiffleDatabase.MIGRATION_4_5,
    RiffleDatabase.MIGRATION_5_6,
    RiffleDatabase.MIGRATION_6_7,
    RiffleDatabase.MIGRATION_7_8,
    RiffleDatabase.MIGRATION_8_9,
    RiffleDatabase.MIGRATION_9_10,
    RiffleDatabase.MIGRATION_10_11,
    RiffleDatabase.MIGRATION_11_12,
    RiffleDatabase.MIGRATION_12_13,
    RiffleDatabase.MIGRATION_13_14,
    RiffleDatabase.MIGRATION_14_15,
    RiffleDatabase.MIGRATION_15_16,
    RiffleDatabase.MIGRATION_16_17,
    RiffleDatabase.MIGRATION_17_18,
    RiffleDatabase.MIGRATION_18_19,
    RiffleDatabase.MIGRATION_19_20,
    RiffleDatabase.MIGRATION_20_21,
    RiffleDatabase.MIGRATION_21_22,
    RiffleDatabase.MIGRATION_22_23,
    RiffleDatabase.MIGRATION_23_24,
    RiffleDatabase.MIGRATION_24_25,
    RiffleDatabase.MIGRATION_25_26,
    RiffleDatabase.MIGRATION_26_27,
    RiffleDatabase.MIGRATION_27_28,
    RiffleDatabase.MIGRATION_28_29,
    RiffleDatabase.MIGRATION_29_30,
    RiffleDatabase.MIGRATION_30_31,
    RiffleDatabase.MIGRATION_31_32,
    RiffleDatabase.MIGRATION_32_33,
    RiffleDatabase.MIGRATION_33_34,
    RiffleDatabase.MIGRATION_34_35,
    RiffleDatabase.MIGRATION_35_36,
    RiffleDatabase.MIGRATION_36_37,
    RiffleDatabase.MIGRATION_37_38,
    RiffleDatabase.MIGRATION_38_39,
    RiffleDatabase.MIGRATION_39_40,
    RiffleDatabase.MIGRATION_40_41,
    RiffleDatabase.MIGRATION_41_42,
    RiffleDatabase.MIGRATION_42_43,
    RiffleDatabase.MIGRATION_43_44,
    RiffleDatabase.MIGRATION_44_45,
    RiffleDatabase.MIGRATION_45_46,
    RiffleDatabase.MIGRATION_46_47,
    RiffleDatabase.MIGRATION_47_48,
    RiffleDatabase.MIGRATION_48_49,
    RiffleDatabase.MIGRATION_49_50,
    RiffleDatabase.MIGRATION_50_51,
    RiffleDatabase.MIGRATION_51_52,
    RiffleDatabase.MIGRATION_52_53,
    RiffleDatabase.MIGRATION_53_54,
    RiffleDatabase.MIGRATION_54_55,
    RiffleDatabase.MIGRATION_55_56,
    RiffleDatabase.MIGRATION_56_57,
    RiffleDatabase.MIGRATION_57_58,
    RiffleDatabase.MIGRATION_58_59,
    RiffleDatabase.MIGRATION_59_60,
    RiffleDatabase.MIGRATION_60_61,
    RiffleDatabase.MIGRATION_61_62,
    RiffleDatabase.MIGRATION_62_63,
    RiffleDatabase.MIGRATION_63_64,
    RiffleDatabase.MIGRATION_64_65,
    RiffleDatabase.MIGRATION_65_66,
    RiffleDatabase.MIGRATION_66_67,
    RiffleDatabase.MIGRATION_67_68,
    RiffleDatabase.MIGRATION_68_69,
)
