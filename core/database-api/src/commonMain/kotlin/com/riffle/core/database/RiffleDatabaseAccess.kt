package com.riffle.core.database

/**
 * Platform-neutral access to Riffle's DAO surface.
 *
 * Hosting modules depend on this contract rather than the generated database implementation.
 * That keeps Room construction, drivers, and migrations confined to `core:database` while
 * repositories can be wired against the DAO interfaces.
 */
interface RiffleDatabaseAccess {
    fun close()
    fun sourceDao(): SourceDao
    fun libraryDao(): LibraryDao
    fun libraryItemDao(): LibraryItemDao
    fun seriesDao(): SeriesDao
    fun collectionDao(): CollectionDao
    fun readingPositionDao(): ReadingPositionDao
    fun bookFormattingPreferencesDao(): BookFormattingPreferencesDao
    fun readaloudLinkDao(): ReadaloudLinkDao
    fun readaloudCandidateDao(): ReadaloudCandidateDao
    fun readaloudDismissalDao(): ReadaloudDismissalDao
    fun crossEpubIndexDao(): CrossEpubIndexDao
    fun annotationDao(): AnnotationDao
    fun readaloudResumePositionDao(): ReadaloudResumePositionDao
    fun audioPlaybackPreferencesDao(): AudioPlaybackPreferencesDao
    fun audiobookPositionDao(): AudiobookPositionDao
    fun audiobookBookmarkDao(): AudiobookBookmarkDao
    fun tocCacheDao(): TocCacheDao
    fun audiobookChapterCacheDao(): AudiobookChapterCacheDao
    fun localFilesFolderDao(): LocalFilesFolderDao
    fun localFilesFileDao(): LocalFilesFileDao
    fun localFilesFileFolderDao(): LocalFilesFileFolderDao
    fun localFileMetadataOverrideDao(): LocalFileMetadataOverrideDao
    fun remoteItemFreshnessDao(): RemoteItemFreshnessDao
    fun playlistDao(): PlaylistDao
    fun publicationMetricsCacheDao(): PublicationMetricsCacheDao
    fun bookComicFormattingPreferencesDao(): BookComicFormattingPreferencesDao
    fun dictionaryPackDao(): DictionaryPackDao
    fun lookupHistoryDao(): LookupHistoryDao
    fun coverGridScaleDao(): CoverGridScaleDao
}
