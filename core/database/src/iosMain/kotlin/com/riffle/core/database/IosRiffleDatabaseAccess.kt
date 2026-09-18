package com.riffle.core.database

import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.dao.IosAnnotationDao
import com.riffle.core.database.dao.IosAudioPlaybackPreferencesDao
import com.riffle.core.database.dao.IosAudiobookBookmarkDao
import com.riffle.core.database.dao.IosAudiobookChapterCacheDao
import com.riffle.core.database.dao.IosAudiobookPositionDao
import com.riffle.core.database.dao.IosBookComicFormattingPreferencesDao
import com.riffle.core.database.dao.IosBookFormattingPreferencesDao
import com.riffle.core.database.dao.IosCollectionDao
import com.riffle.core.database.dao.IosCoverGridScaleDao
import com.riffle.core.database.dao.IosCrossEpubIndexDao
import com.riffle.core.database.dao.IosDictionaryPackDao
import com.riffle.core.database.dao.IosLibraryDao
import com.riffle.core.database.dao.IosLibraryItemDao
import com.riffle.core.database.dao.IosLocalFileMetadataOverrideDao
import com.riffle.core.database.dao.IosLocalFilesFileDao
import com.riffle.core.database.dao.IosLocalFilesFileFolderDao
import com.riffle.core.database.dao.IosLocalFilesFolderDao
import com.riffle.core.database.dao.IosLookupHistoryDao
import com.riffle.core.database.dao.IosPlaylistDao
import com.riffle.core.database.dao.IosPublicationMetricsCacheDao
import com.riffle.core.database.dao.IosReadaloudCandidateDao
import com.riffle.core.database.dao.IosReadaloudDismissalDao
import com.riffle.core.database.dao.IosReadaloudLinkDao
import com.riffle.core.database.dao.IosReadaloudResumePositionDao
import com.riffle.core.database.dao.IosReadingPositionDao
import com.riffle.core.database.dao.IosRemoteItemFreshnessDao
import com.riffle.core.database.dao.IosSeriesDao
import com.riffle.core.database.dao.IosSourceDao
import com.riffle.core.database.dao.IosTocCacheDao

internal class IosRiffleDatabaseAccess(private val driver: SqlDriver) : RiffleDatabaseAccess {
    private val invalidator = IosInvalidator()

    private val sourceDao = IosSourceDao(driver, invalidator)
    private val libraryDao = IosLibraryDao(driver, invalidator)
    private val libraryItemDao = IosLibraryItemDao(driver, invalidator)
    private val tocCacheDao = IosTocCacheDao(driver, invalidator)
    private val playlistDao = IosPlaylistDao(driver, invalidator)
    private val annotationDao = IosAnnotationDao(driver, invalidator)
    private val localFilesFolderDao = IosLocalFilesFolderDao(driver, invalidator)
    private val localFilesFileDao = IosLocalFilesFileDao(driver, invalidator)
    private val localFilesFileFolderDao = IosLocalFilesFileFolderDao(driver, invalidator)
    private val seriesDao = IosSeriesDao(driver, invalidator)
    private val collectionDao = IosCollectionDao(driver, invalidator)
    private val readingPositionDao = IosReadingPositionDao(driver, invalidator)
    private val audiobookPositionDao = IosAudiobookPositionDao(driver, invalidator)
    private val bookFormattingPreferencesDao = IosBookFormattingPreferencesDao(driver, invalidator)
    private val bookComicFormattingPreferencesDao = IosBookComicFormattingPreferencesDao(driver, invalidator)
    private val readaloudLinkDao = IosReadaloudLinkDao(driver, invalidator)
    private val readaloudCandidateDao = IosReadaloudCandidateDao(driver, invalidator)
    private val readaloudDismissalDao = IosReadaloudDismissalDao(driver, invalidator)
    private val crossEpubIndexDao = IosCrossEpubIndexDao(driver, invalidator)
    private val readaloudResumePositionDao = IosReadaloudResumePositionDao(driver, invalidator)
    private val audioPlaybackPreferencesDao = IosAudioPlaybackPreferencesDao(driver, invalidator)
    private val audiobookBookmarkDao = IosAudiobookBookmarkDao(driver, invalidator)
    private val audiobookChapterCacheDao = IosAudiobookChapterCacheDao(driver, invalidator)
    private val localFileMetadataOverrideDao = IosLocalFileMetadataOverrideDao(driver, invalidator)
    private val remoteItemFreshnessDao = IosRemoteItemFreshnessDao(driver, invalidator)
    private val publicationMetricsCacheDao = IosPublicationMetricsCacheDao(driver, invalidator)
    private val dictionaryPackDao = IosDictionaryPackDao(driver, invalidator)
    private val lookupHistoryDao = IosLookupHistoryDao(driver, invalidator)
    private val coverGridScaleDao = IosCoverGridScaleDao(driver, invalidator)

    override fun close() = driver.close()
    override fun sourceDao() = sourceDao
    override fun libraryDao() = libraryDao
    override fun libraryItemDao() = libraryItemDao
    override fun seriesDao() = seriesDao
    override fun collectionDao() = collectionDao
    override fun readingPositionDao() = readingPositionDao
    override fun bookFormattingPreferencesDao() = bookFormattingPreferencesDao
    override fun readaloudLinkDao() = readaloudLinkDao
    override fun readaloudCandidateDao() = readaloudCandidateDao
    override fun readaloudDismissalDao() = readaloudDismissalDao
    override fun crossEpubIndexDao() = crossEpubIndexDao
    override fun annotationDao() = annotationDao
    override fun readaloudResumePositionDao() = readaloudResumePositionDao
    override fun audioPlaybackPreferencesDao() = audioPlaybackPreferencesDao
    override fun audiobookPositionDao() = audiobookPositionDao
    override fun audiobookBookmarkDao() = audiobookBookmarkDao
    override fun tocCacheDao() = tocCacheDao
    override fun audiobookChapterCacheDao() = audiobookChapterCacheDao
    override fun localFilesFolderDao() = localFilesFolderDao
    override fun localFilesFileDao() = localFilesFileDao
    override fun localFilesFileFolderDao() = localFilesFileFolderDao
    override fun localFileMetadataOverrideDao() = localFileMetadataOverrideDao
    override fun remoteItemFreshnessDao() = remoteItemFreshnessDao
    override fun playlistDao() = playlistDao
    override fun publicationMetricsCacheDao() = publicationMetricsCacheDao
    override fun bookComicFormattingPreferencesDao() = bookComicFormattingPreferencesDao
    override fun dictionaryPackDao() = dictionaryPackDao
    override fun lookupHistoryDao() = lookupHistoryDao
    override fun coverGridScaleDao() = coverGridScaleDao
}
