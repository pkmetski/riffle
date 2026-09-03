package com.riffle.core.data.di

import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.openRiffleDatabase
import org.koin.dsl.module

val iosDatabaseModule = module {
    // NativeSqliteDriver 2.0.x only accepts a plain filename (no path separators).
    // SQLite opens the file relative to the process working directory on iOS.
    single<RiffleDatabaseAccess> { openRiffleDatabase("riffle.db") }
    single { get<RiffleDatabaseAccess>().sourceDao() }
    single { get<RiffleDatabaseAccess>().libraryDao() }
    single { get<RiffleDatabaseAccess>().libraryItemDao() }
    single { get<RiffleDatabaseAccess>().seriesDao() }
    single { get<RiffleDatabaseAccess>().collectionDao() }
    single { get<RiffleDatabaseAccess>().readingPositionDao() }
    single { get<RiffleDatabaseAccess>().readaloudResumePositionDao() }
    single { get<RiffleDatabaseAccess>().bookFormattingPreferencesDao() }
    single { get<RiffleDatabaseAccess>().audioPlaybackPreferencesDao() }
    single { get<RiffleDatabaseAccess>().audiobookPositionDao() }
    single { get<RiffleDatabaseAccess>().audiobookBookmarkDao() }
    single { get<RiffleDatabaseAccess>().readaloudLinkDao() }
    single { get<RiffleDatabaseAccess>().readaloudCandidateDao() }
    single { get<RiffleDatabaseAccess>().readaloudDismissalDao() }
    single { get<RiffleDatabaseAccess>().crossEpubIndexDao() }
    single { get<RiffleDatabaseAccess>().annotationDao() }
    single { get<RiffleDatabaseAccess>().tocCacheDao() }
    single { get<RiffleDatabaseAccess>().audiobookChapterCacheDao() }
    single { get<RiffleDatabaseAccess>().localFilesFolderDao() }
    single { get<RiffleDatabaseAccess>().localFilesFileDao() }
    single { get<RiffleDatabaseAccess>().localFilesFileFolderDao() }
    single { get<RiffleDatabaseAccess>().localFileMetadataOverrideDao() }
    single { get<RiffleDatabaseAccess>().remoteItemFreshnessDao() }
    single { get<RiffleDatabaseAccess>().playlistDao() }
    single { get<RiffleDatabaseAccess>().publicationMetricsCacheDao() }
    single { get<RiffleDatabaseAccess>().bookComicFormattingPreferencesDao() }
    single { get<RiffleDatabaseAccess>().dictionaryPackDao() }
    single { get<RiffleDatabaseAccess>().lookupHistoryDao() }
    single { get<RiffleDatabaseAccess>().coverGridScaleDao() }
}
