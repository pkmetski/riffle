package com.riffle.core.database

import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.dao.IosAnnotationDao
import com.riffle.core.database.dao.IosCollectionDao
import com.riffle.core.database.dao.IosLibraryDao
import com.riffle.core.database.dao.IosLibraryItemDao
import com.riffle.core.database.dao.IosLocalFilesFileDao
import com.riffle.core.database.dao.IosLocalFilesFileFolderDao
import com.riffle.core.database.dao.IosLocalFilesFolderDao
import com.riffle.core.database.dao.IosNoOpAudioPlaybackPreferencesDao
import com.riffle.core.database.dao.IosNoOpAudiobookBookmarkDao
import com.riffle.core.database.dao.IosNoOpAudiobookChapterCacheDao
import com.riffle.core.database.dao.IosNoOpAudiobookPositionDao
import com.riffle.core.database.dao.IosNoOpBookComicFormattingPreferencesDao
import com.riffle.core.database.dao.IosNoOpBookFormattingPreferencesDao
import com.riffle.core.database.dao.IosNoOpCoverGridScaleDao
import com.riffle.core.database.dao.IosNoOpCrossEpubIndexDao
import com.riffle.core.database.dao.IosNoOpDictionaryPackDao
import com.riffle.core.database.dao.IosNoOpLocalFileMetadataOverrideDao
import com.riffle.core.database.dao.IosNoOpLookupHistoryDao
import com.riffle.core.database.dao.IosNoOpPublicationMetricsCacheDao
import com.riffle.core.database.dao.IosNoOpReadaloudCandidateDao
import com.riffle.core.database.dao.IosNoOpReadaloudDismissalDao
import com.riffle.core.database.dao.IosNoOpReadaloudLinkDao
import com.riffle.core.database.dao.IosNoOpReadaloudResumePositionDao
import com.riffle.core.database.dao.IosNoOpReadingPositionDao
import com.riffle.core.database.dao.IosNoOpRemoteItemFreshnessDao
import com.riffle.core.database.dao.IosPlaylistDao
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

    override fun close() = driver.close()
    override fun sourceDao() = sourceDao
    override fun libraryDao() = libraryDao
    override fun libraryItemDao() = libraryItemDao
    override fun seriesDao() = seriesDao
    override fun collectionDao() = collectionDao
    override fun readingPositionDao() = IosNoOpReadingPositionDao
    override fun bookFormattingPreferencesDao() = IosNoOpBookFormattingPreferencesDao
    override fun readaloudLinkDao() = IosNoOpReadaloudLinkDao
    override fun readaloudCandidateDao() = IosNoOpReadaloudCandidateDao
    override fun readaloudDismissalDao() = IosNoOpReadaloudDismissalDao
    override fun crossEpubIndexDao() = IosNoOpCrossEpubIndexDao
    override fun annotationDao() = annotationDao
    override fun readaloudResumePositionDao() = IosNoOpReadaloudResumePositionDao
    override fun audioPlaybackPreferencesDao() = IosNoOpAudioPlaybackPreferencesDao
    override fun audiobookPositionDao() = IosNoOpAudiobookPositionDao
    override fun audiobookBookmarkDao() = IosNoOpAudiobookBookmarkDao
    override fun tocCacheDao() = tocCacheDao
    override fun audiobookChapterCacheDao() = IosNoOpAudiobookChapterCacheDao
    override fun localFilesFolderDao() = localFilesFolderDao
    override fun localFilesFileDao() = localFilesFileDao
    override fun localFilesFileFolderDao() = localFilesFileFolderDao
    override fun localFileMetadataOverrideDao() = IosNoOpLocalFileMetadataOverrideDao
    override fun remoteItemFreshnessDao() = IosNoOpRemoteItemFreshnessDao
    override fun playlistDao() = playlistDao
    override fun publicationMetricsCacheDao() = IosNoOpPublicationMetricsCacheDao
    override fun bookComicFormattingPreferencesDao() = IosNoOpBookComicFormattingPreferencesDao
    override fun dictionaryPackDao() = IosNoOpDictionaryPackDao
    override fun lookupHistoryDao() = IosNoOpLookupHistoryDao
    override fun coverGridScaleDao() = IosNoOpCoverGridScaleDao
}
