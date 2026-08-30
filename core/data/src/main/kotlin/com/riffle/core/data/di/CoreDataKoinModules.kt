package com.riffle.core.data.di

import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.DefaultCatalogRegistry
import com.riffle.core.catalog.abs.AbsCatalogFactory
import com.riffle.core.catalog.chitanka.ChitankaCatalogFactory
import com.riffle.core.catalog.gutenberg.GutenbergCatalogFactory
import com.riffle.core.catalog.komga.KomgaCatalogFactory
import com.riffle.core.catalog.radioes.RadioEsCatalogFactory
import com.riffle.core.common.EncryptedKeyValueStore
import com.riffle.core.common.FileStore
import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.data.AndroidDeviceLabelResolver
import com.riffle.core.data.AnnotationSyncConfigStoreImpl
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.data.AnnotationSyncController
import com.riffle.core.data.AnnotationSyncMaintenance
import com.riffle.core.data.AnnotationSyncTargetHolder
import com.riffle.core.data.AnnotationSweep
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.data.AnnotationsLibraryRepositoryImpl
import com.riffle.core.data.AudioIdentityResolverImpl
import com.riffle.core.data.AudiobookBookmarkSyncStoreImpl
import com.riffle.core.domain.AudiobookBookmarkSyncStore
import com.riffle.core.data.AudiobookBundleDownloader
import com.riffle.core.data.AudiobookCacheRepositoryImpl
import com.riffle.core.data.AudiobookChapterCacheRepositoryImpl
import com.riffle.core.data.AudiobookDownloadRepositoryImpl
import com.riffle.core.data.AudiobookPositionStoreImpl
import com.riffle.core.data.AudiobookRepositoryImpl
import com.riffle.core.data.AudioPlaybackPreferencesStoreImpl
import com.riffle.core.data.BookFormattingPreferencesStoreImpl
import com.riffle.core.data.CatalogProgressRemoteFactory
import com.riffle.core.data.CatalogRemoteProgressIndex
import com.riffle.core.data.CatalogSyncSourceResolver
import com.riffle.core.data.CbzRepositoryImpl
import com.riffle.core.data.ComicFormattingPreferencesStoreImpl
import com.riffle.core.data.ConnectivityObserverImpl
import com.riffle.core.data.ContentCacheAccessStoreImpl
import com.riffle.core.data.ContentCacheArtifactScannerImpl
import com.riffle.core.data.CoverGridDensityStoreImpl
import com.riffle.core.data.CrashReportRepositoryImpl
import com.riffle.core.data.CrossEpubIndexBuilderService
import com.riffle.core.data.CrossEpubIndexBuildTrigger
import com.riffle.core.data.CrossEpubIndexStoreImpl
import com.riffle.core.data.DeviceIdStoreImpl
import com.riffle.core.data.DeviceLabelStoreImpl
import com.riffle.core.data.DeviceMetaSentinelWriter
import com.riffle.core.data.DownloadsRepositoryImpl
import com.riffle.core.data.EpubRepositoryImpl
import com.riffle.core.data.FilesdirFileStore
import com.riffle.core.data.FormattingPreferencesStoreImpl
import com.riffle.core.data.FormattingPreferencesStoreProviderImpl
import com.riffle.core.data.ItemProgressPuller
import com.riffle.core.data.KeystoreEncryptedKeyValueStore
import com.riffle.core.data.KeystoreTokenStorage
import com.riffle.core.data.LastOpenedLibraryStoreImpl
import com.riffle.core.data.LibraryFilterPreferencesStoreImpl
import com.riffle.core.data.LibraryItemUiProgressSink
import com.riffle.core.data.LibraryOrderPreferencesStoreImpl
import com.riffle.core.data.LibraryRepositoryImpl
import com.riffle.core.data.LibraryVisibilityPreferencesStoreImpl
import com.riffle.core.data.ListeningPreferencesStoreImpl
import com.riffle.core.data.LocalAvailabilityEventsImpl
import com.riffle.core.data.LocalStoreImpl
import com.riffle.core.data.LocalStoreMigrator
import com.riffle.core.data.LocalToReadStore
import com.riffle.core.data.LocalToReadStoreImpl
import com.riffle.core.data.OfflineAvailabilitySnapshot
import com.riffle.core.data.PdfRepositoryImpl
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.PlaylistsRepositoryImpl
import com.riffle.core.data.PublicationMetricsRepositoryImpl
import com.riffle.core.data.ReadaloudAudioRepositoryImpl
import com.riffle.core.data.ReadaloudLinkRepositoryImpl
import com.riffle.core.data.ReadaloudMatchingService
import com.riffle.core.data.ReadaloudResumeStoreImpl
import com.riffle.core.data.ReadaloudReviewRepositoryImpl
import com.riffle.core.data.ReadaloudSidecarPrefetcher
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.ReadingSessionRepositoryImpl
import com.riffle.core.data.ReconcilingItemProgressPuller
import com.riffle.core.data.RoomDirtyAnnotationLedger
import com.riffle.core.data.RoomDirtyProgressLedger
import com.riffle.core.data.SourceFilesCleanerImpl
import com.riffle.core.data.SourceRepositoryImpl
import com.riffle.core.data.StorytellerBundleAudiobookSource
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.data.StorytellerSidecarFetcher
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.ToReadRepositoryImpl
import com.riffle.core.data.TocRepositoryImpl
import com.riffle.core.data.VolumeKeyPreferencesStoreImpl
import com.riffle.core.data.WebSourceLibraryItemMaterializer
import com.riffle.core.data.absbookmark.AbsBookmarkAnnotationSyncTargetFactory
import com.riffle.core.data.comic.panel.AndroidPageImageDecoder
import com.riffle.core.data.comic.panel.AndroidPanelMaskServiceImpl
import com.riffle.core.data.comic.panel.GitHubPanelReportRepository
import com.riffle.core.data.comic.panel.JsonPanelStore
import com.riffle.core.data.developer.AndroidPatStore
import com.riffle.core.data.developer.DeveloperOptionsRepositoryImpl
import com.riffle.core.data.developer.PatStore
import com.riffle.core.data.dictionary.DictionaryPackSqliteStore
import com.riffle.core.data.dictionary.KaikkiJsonlToSqliteConverter
import com.riffle.core.data.dictionary.PackDownloader
import com.riffle.core.data.dictionary.WordLookupRepositoryImpl
import com.riffle.core.data.localfiles.AndroidCopyInService
import com.riffle.core.data.localfiles.CopyInService
import com.riffle.core.data.localfiles.FolderWalker
import com.riffle.core.data.localfiles.LocalFilesCatalogFactory
import com.riffle.core.data.localfiles.SafFolderWalker
import com.riffle.core.data.readaloudLinksByAbsItemKey
import com.riffle.core.data.sync.AbsRemoteUserIdResolver
import com.riffle.core.data.sync.KomgaRemoteUserIdResolver
import com.riffle.core.dictionary.DictionaryRepository
import com.riffle.core.dictionary.PackStore
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifactScanner
import com.riffle.core.domain.ContentCacheCleaner
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.DeviceLabelStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.EmphasisPreferencesStore
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferencesStoreProvider
import com.riffle.core.domain.HighlightColorPreferencesStore
import com.riffle.core.domain.HighlightsResumeStore
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudReviewMutator
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.ReadaloudSidecarCache
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.domain.SourceFilesCleaner
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.domain.TocRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.domain.WebSourceRegistry
import com.riffle.core.domain.comic.BookComicFormattingPreferencesStore
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.comic.panel.PageImageDecoder
import com.riffle.core.domain.comic.panel.PanelDetectionConfig
import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelEngine
import com.riffle.core.domain.comic.panel.PanelMaskService
import com.riffle.core.domain.comic.panel.PanelOrchestrator
import com.riffle.core.domain.comic.panel.PanelReportRepository
import com.riffle.core.domain.comic.panel.PanelStore
import com.riffle.core.domain.comic.panel.PanelViewPreferencesStore
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.AbsFileDownloadApi
import com.riffle.core.network.AbsFileDownloadApiClient
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.AudiobookBundleApiImpl
import com.riffle.core.network.GitHubReleaseApi
import com.riffle.core.network.JvmHttpClientPool
import com.riffle.core.network.KomgaServerInfoApi
import com.riffle.core.network.KomgaServerInfoApiClient
import com.riffle.core.network.StorytellerApi
import com.riffle.core.network.StorytellerApiClient
import com.riffle.core.network.StorytellerBundleApiImpl
import com.riffle.core.network.StorytellerLibraryApi
import com.riffle.core.network.StorytellerPositionApi
import com.riffle.core.network.StorytellerPositionApiImpl
import com.riffle.core.network.createDefaultJvmHttpClientPool
import com.riffle.core.network.createWebSourceHttpClient
import com.riffle.core.database.openRiffleDatabase
import com.riffle.core.sources.SourceAdapter
import com.riffle.core.sources.abs.AbsSourceAdapter
import com.riffle.core.sources.komga.KomgaSourceAdapter
import com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory
import com.riffle.core.sources.webdav.WebDavProgressEnumerator
import com.riffle.core.sources.webdav.WebDavProgressRemoteFactory
import com.riffle.core.sync.AnnotationSyncStatusStore
import com.riffle.core.sync.AudiobookBookmarkReconciler
import com.riffle.core.sync.BookmarkReconcile
import com.riffle.core.sync.DirtyAnnotationLedger as SyncDirtyAnnotationLedger
import com.riffle.core.sync.DirtyBookmarkLedger
import com.riffle.core.sync.DirtyProgressLedger
import com.riffle.core.sync.OpenReconcileTargets
import com.riffle.core.sync.ProgressRemoteFactory
import com.riffle.core.sync.ProgressSweep
import com.riffle.core.sync.ReconcileLocks
import com.riffle.core.sync.RemoteProgressIndex
import com.riffle.core.sync.SyncSourceResolver
import io.ktor.client.HttpClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.riffle.core.data.credentialed.CredentialedSourceInstaller
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

// Named qualifier strings for File instances
private const val EPUB_CACHE_DIR = "epubCacheDir"
private const val PDF_CACHE_DIR = "pdfCacheDir"
private const val CBZ_CACHE_DIR = "cbzCacheDir"
private const val AUDIOBOOK_CACHE_DIR = "audiobookCacheDir"
private const val AUDIOBOOK_DOWNLOADS_DIR = "audiobookDownloadsDir"
private const val CRASH_REPORT_DIR = "crashReportDir"

// Named qualifier strings for LocalStore instances
private const val EPUB_CACHE_STORE = "epubCacheStore"
private const val EPUB_DOWNLOADS_STORE = "epubDownloadsStore"
private const val PDF_CACHE_STORE = "pdfCacheStore"
private const val PDF_DOWNLOADS_STORE = "pdfDownloadsStore"
private const val CBZ_CACHE_STORE = "cbzCacheStore"
private const val CBZ_DOWNLOADS_STORE = "cbzDownloadsStore"

// Named qualifier strings for HttpClient instances
private const val STREAMING_HTTP_CLIENT = "streamingHttpClient"
private const val WEB_SOURCE_HTTP_CLIENT = "webSourceHttpClient"

// Named qualifier strings for DataStore<Preferences> instances
private const val DS_FORMATTING = "formattingPreferencesDataStore"
private const val DS_HIGHLIGHTS_FORMATTING = "highlightsFormattingPreferencesDataStore"
private const val DS_LIBRARY_VISIBILITY = "libraryVisibilityPreferencesDataStore"
private const val DS_LIBRARY_FILTER = "libraryFilterPreferencesDataStore"
private const val DS_LOCAL_TO_READ = "localToReadDataStore"
private const val DS_LIBRARY_ORDER = "libraryOrderPreferencesDataStore"
private const val DS_LAST_OPENED_LIBRARY = "lastOpenedLibraryDataStore"
private const val DS_WAKE_LOCK = "wakeLockPreferencesDataStore"
private const val DS_LISTENING = "listeningPreferencesDataStore"
private const val DS_VOLUME_KEY = "volumeKeyPreferencesDataStore"
private const val DS_APP_THEME = "appThemePreferencesDataStore"
private const val DS_COVER_GRID_DENSITY = "coverGridDensityDataStore"
private const val DS_DEVICE_ID = "deviceIdDataStore"
private const val DS_DEVICE_LABEL = "deviceLabelDataStore"
private const val DS_READALOUD = "readaloudPreferencesDataStore"
private const val DS_HIGHLIGHT_COLOR = "highlightColorPreferencesDataStore"
private const val DS_READING_SPEED = "readingSpeedDataStore"
private const val DS_HIGHLIGHTS_RESUME = "highlightsResumePreferencesDataStore"
private const val DS_CONTENT_CACHE_SETTINGS = "contentCacheSettingsDataStore"
private const val DS_CONTENT_CACHE_ACCESS = "contentCacheAccessDataStore"
private const val DS_APP_UPDATE = "appUpdatePreferencesDataStore"
private const val DS_COMIC_FORMATTING = "comicFormattingPreferencesDataStore"
private const val DS_DEVELOPER_OPTIONS = "developerOptionsDataStore"
private const val DS_PANEL_VIEW = "panelViewPreferencesDataStore"

private const val DEFAULT_HTTP_CACHE_BYTES: Long = 20L * 1024L * 1024L
private const val WEB_SOURCE_CACHE_BYTES: Long = 10L * 1024L * 1024L
private const val WEB_SOURCE_MAX_AGE_SECONDS: Int = 24 * 60 * 60

private val coreDataDatabaseModule = module {
    single { openRiffleDatabase(androidContext()) }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().sourceDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().libraryDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().libraryItemDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().seriesDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().collectionDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().readingPositionDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().readaloudResumePositionDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().bookFormattingPreferencesDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().audioPlaybackPreferencesDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().audiobookPositionDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().audiobookBookmarkDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().readaloudLinkDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().readaloudCandidateDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().readaloudDismissalDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().crossEpubIndexDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().annotationDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().tocCacheDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().publicationMetricsCacheDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().audiobookChapterCacheDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().localFilesFolderDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().localFilesFileDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().localFilesFileFolderDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().localFileMetadataOverrideDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().remoteItemFreshnessDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().playlistDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().bookComicFormattingPreferencesDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().dictionaryPackDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().lookupHistoryDao() }
    single { get<com.riffle.core.database.RiffleDatabaseAccess>().coverGridScaleDao() }
}

private val coreDataLocalStoreModule = module {
    single<FileStore> { FilesdirFileStore(androidContext()) }
    single<TokenStorage> { KeystoreTokenStorage(androidContext(), get()) }
    single<EncryptedKeyValueStore> { KeystoreEncryptedKeyValueStore(androidContext()) }
    single<AnnotationStore> { AnnotationStoreImpl(get(), get(), get()) }
    single<CrossEpubIndexStore> { CrossEpubIndexStoreImpl(get()) }

    // ReadingPositionStoreImpl: implements ReadingPositionStore and SyncPositionStore<String>
    single { ReadingPositionStoreImpl(get(), get()) }
    single<ReadingPositionStore> { get<ReadingPositionStoreImpl>() }
    single<SyncPositionStore<String>> { get<ReadingPositionStoreImpl>() }

    // AudiobookPositionStoreImpl: implements AudiobookPositionStore and SyncPositionStore<Double>
    single { AudiobookPositionStoreImpl(get(), get()) }
    single<AudiobookPositionStore> { get<AudiobookPositionStoreImpl>() }
    single<SyncPositionStore<Double>> { get<AudiobookPositionStoreImpl>() }

    single<AudiobookBookmarkStore> { com.riffle.core.data.AudiobookBookmarkStoreImpl(get()) }
    single<ReadaloudResumeStore> { ReadaloudResumeStoreImpl(get(), get()) }
    single<DeviceIdStore> { DeviceIdStoreImpl(get(named(DS_DEVICE_ID))) }
    single<DeviceLabelStore> { DeviceLabelStoreImpl(get(named(DS_DEVICE_LABEL))) }
    single<DeviceLabelResolver> { AndroidDeviceLabelResolver(androidContext(), get()) }
    single<ContentCacheArtifactScanner> {
        ContentCacheArtifactScannerImpl(
            epubCacheDir = get(named(EPUB_CACHE_DIR)),
            pdfCacheDir = get(named(PDF_CACHE_DIR)),
            audiobookCacheDir = get(named(AUDIOBOOK_CACHE_DIR)),
            cbzCacheDir = get(named(CBZ_CACHE_DIR)),
        )
    }

    // Named File qualifiers
    single<File>(named(CRASH_REPORT_DIR)) {
        File(androidContext().filesDir, "crash_reports").apply { mkdirs() }
    }
    single<File>(named(EPUB_CACHE_DIR)) {
        androidContext().cacheDir.resolve("epubs").also { it.mkdirs() }
    }
    single<File>(named(PDF_CACHE_DIR)) {
        androidContext().cacheDir.resolve("pdfs").also { it.mkdirs() }
    }
    single<File>(named(CBZ_CACHE_DIR)) {
        androidContext().cacheDir.resolve("cbz").also { it.mkdirs() }
    }
    single<File>(named(AUDIOBOOK_CACHE_DIR)) {
        androidContext().cacheDir.resolve("audiobooks").also { it.mkdirs() }
    }
    single<File>(named(AUDIOBOOK_DOWNLOADS_DIR)) {
        androidContext().filesDir.resolve("downloads/audiobooks").also { it.mkdirs() }
    }

    // Named LocalStore qualifiers
    single<LocalStore>(named(EPUB_CACHE_STORE)) {
        LocalStoreImpl(get(named(EPUB_CACHE_DIR)), ".epub", get())
    }
    single<LocalStore>(named(EPUB_DOWNLOADS_STORE)) {
        LocalStoreImpl(
            androidContext().filesDir.resolve("downloads/epubs").also { it.mkdirs() },
            ".epub",
            get(),
        )
    }
    single<LocalStore>(named(PDF_CACHE_STORE)) {
        LocalStoreImpl(get(named(PDF_CACHE_DIR)), ".pdf", get())
    }
    single<LocalStore>(named(PDF_DOWNLOADS_STORE)) {
        LocalStoreImpl(
            androidContext().filesDir.resolve("downloads/pdfs").also { it.mkdirs() },
            ".pdf",
            get(),
        )
    }
    single<LocalStore>(named(CBZ_CACHE_STORE)) {
        LocalStoreImpl(get(named(CBZ_CACHE_DIR)), ".cbz", get())
    }
    single<LocalStore>(named(CBZ_DOWNLOADS_STORE)) {
        LocalStoreImpl(
            androidContext().filesDir.resolve("downloads/cbz").also { it.mkdirs() },
            ".cbz",
            get(),
        )
    }

    single {
        val ctx = androidContext()
        val dispatchers = get<DispatcherProvider>()
        val libraryItemDao = get<com.riffle.core.database.LibraryItemDao>()
        LocalStoreMigrator(
            stores = listOf(
                ctx.cacheDir.resolve("epubs") to ".epub",
                ctx.filesDir.resolve("downloads/epubs") to ".epub",
                ctx.cacheDir.resolve("pdfs") to ".pdf",
                ctx.filesDir.resolve("downloads/pdfs") to ".pdf",
                ctx.cacheDir.resolve("cbz") to ".cbz",
                ctx.filesDir.resolve("downloads/cbz") to ".cbz",
            ),
            resolveServerId = { itemId -> libraryItemDao.findSourceIdForItem(itemId) },
            dispatchers = dispatchers,
        )
    }

    single<DownloadsRepository> {
        DownloadsRepositoryImpl(
            epubCacheStore = get(named(EPUB_CACHE_STORE)),
            epubDownloadsStore = get(named(EPUB_DOWNLOADS_STORE)),
            pdfCacheStore = get(named(PDF_CACHE_STORE)),
            pdfDownloadsStore = get(named(PDF_DOWNLOADS_STORE)),
            cbzCacheStore = get(named(CBZ_CACHE_STORE)),
            cbzDownloadsStore = get(named(CBZ_DOWNLOADS_STORE)),
            audiobookCacheDir = get(named(AUDIOBOOK_CACHE_DIR)),
            audiobookDownloadsDir = get(named(AUDIOBOOK_DOWNLOADS_DIR)),
            localAvailabilityEvents = get(),
        )
    }

    single<SourceFilesCleaner> {
        SourceFilesCleanerImpl(
            stores = listOf(
                get(named(EPUB_CACHE_STORE)),
                get(named(EPUB_DOWNLOADS_STORE)),
                get(named(PDF_CACHE_STORE)),
                get(named(PDF_DOWNLOADS_STORE)),
                get(named(CBZ_CACHE_STORE)),
                get(named(CBZ_DOWNLOADS_STORE)),
            ),
            audiobookDownloadsDir = get(named(AUDIOBOOK_DOWNLOADS_DIR)),
            dispatchers = get(),
        )
    }

    single {
        ContentCacheCleaner(
            settingsStore = get(),
            accessStore = get(),
            artifactScanner = get(),
            clock = get(),
            dispatchers = get(),
            onRemoved = { key -> get<LocalAvailabilityEvents>().notifyChanged(key.sourceId, key.itemId) },
        )
    }
}

private val coreDataPreferencesModule = module {
    // DataStore<Preferences> instances — each named to disambiguate
    single(named(DS_FORMATTING)) { androidContext().formattingPreferencesDataStore }
    single(named(DS_HIGHLIGHTS_FORMATTING)) { androidContext().formattingPreferencesHighlightsDataStore }
    single(named(DS_LIBRARY_VISIBILITY)) { androidContext().libraryVisibilityPreferencesDataStore }
    single(named(DS_LIBRARY_FILTER)) { androidContext().libraryFilterPreferencesDataStore }
    single(named(DS_LOCAL_TO_READ)) { androidContext().localToReadDataStore }
    single(named(DS_LIBRARY_ORDER)) { androidContext().libraryOrderPreferencesDataStore }
    single(named(DS_LAST_OPENED_LIBRARY)) { androidContext().lastOpenedLibraryDataStore }
    single(named(DS_WAKE_LOCK)) { androidContext().wakeLockPreferencesDataStore }
    single(named(DS_LISTENING)) { androidContext().listeningPreferencesDataStore }
    single(named(DS_VOLUME_KEY)) { androidContext().volumeKeyPreferencesDataStore }
    single(named(DS_APP_THEME)) { androidContext().appThemePreferencesDataStore }
    single(named(DS_COVER_GRID_DENSITY)) { androidContext().coverGridDensityDataStore }
    single(named(DS_DEVICE_ID)) { androidContext().deviceIdDataStore }
    single(named(DS_DEVICE_LABEL)) { androidContext().deviceLabelDataStore }
    single(named(DS_READALOUD)) { androidContext().readaloudPreferencesDataStore }
    single(named(DS_HIGHLIGHT_COLOR)) { androidContext().highlightColorPreferencesDataStore }
    single(named(DS_READING_SPEED)) { androidContext().readingSpeedDataStore }
    single(named(DS_HIGHLIGHTS_RESUME)) { androidContext().highlightsResumePreferencesDataStore }
    single(named(DS_CONTENT_CACHE_SETTINGS)) { androidContext().contentCacheSettingsDataStore }
    single(named(DS_CONTENT_CACHE_ACCESS)) { androidContext().contentCacheAccessDataStore }
    single(named(DS_APP_UPDATE)) { androidContext().appUpdatePreferencesDataStore }
    single(named(DS_COMIC_FORMATTING)) { androidContext().comicFormattingPreferencesDataStore }

    // Preference store implementations — concrete registration first where needed as dependency
    single { FormattingPreferencesStoreImpl(get(named(DS_FORMATTING))) }
    single<FormattingPreferencesStore> { get<FormattingPreferencesStoreImpl>() }

    single<FormattingPreferencesStoreProvider> {
        FormattingPreferencesStoreProviderImpl(fullBook = get<FormattingPreferencesStoreImpl>())
    }

    single<BookFormattingPreferencesStore> { BookFormattingPreferencesStoreImpl(get(), get()) }
    single<AudioPlaybackPreferencesStore> { AudioPlaybackPreferencesStoreImpl(get()) }
    single<LibraryVisibilityPreferencesStore> {
        LibraryVisibilityPreferencesStoreImpl(get(named(DS_LIBRARY_VISIBILITY)))
    }
    single<LibraryFilterPreferencesStore> {
        LibraryFilterPreferencesStoreImpl(get(named(DS_LIBRARY_FILTER)))
    }
    single<LibraryOrderPreferencesStore> {
        LibraryOrderPreferencesStoreImpl(get(named(DS_LIBRARY_ORDER)))
    }
    single<LastOpenedLibraryStore> { LastOpenedLibraryStoreImpl(get(named(DS_LAST_OPENED_LIBRARY))) }
    single<ListeningPreferencesStore> { ListeningPreferencesStoreImpl(get(named(DS_LISTENING))) }
    single<VolumeKeyPreferencesStore> { VolumeKeyPreferencesStoreImpl(get(named(DS_VOLUME_KEY))) }
    single<ContentCacheAccessStore> { ContentCacheAccessStoreImpl(get(named(DS_CONTENT_CACHE_ACCESS)), get()) }
    single<ComicFormattingPreferencesStore> {
        ComicFormattingPreferencesStoreImpl(get(named(DS_COMIC_FORMATTING)))
    }

    // Single-key DataStore wrappers
    single<AppThemeStore> { com.riffle.core.data.AppThemeStore(get(named(DS_APP_THEME))) }
    single<CoverGridDensityStore> {
        CoverGridDensityStoreImpl(get(named(DS_COVER_GRID_DENSITY)), get())
    }
    single<ReadingSpeedStore> { com.riffle.core.data.ReadingSpeedStore(get(named(DS_READING_SPEED))) }
    single<WakeLockPreferencesStore> {
        com.riffle.core.data.WakeLockPreferencesStore(get(named(DS_WAKE_LOCK)))
    }
    single<ReadaloudPreferencesStore> {
        com.riffle.core.data.ReadaloudPreferencesStore(get(named(DS_READALOUD)))
    }
    single<HighlightColorPreferencesStore> {
        com.riffle.core.data.HighlightColorPreferencesStore(get(named(DS_HIGHLIGHT_COLOR)))
    }
    single<EmphasisPreferencesStore> {
        com.riffle.core.data.EmphasisPreferencesStore(get(named(DS_HIGHLIGHT_COLOR)))
    }
    single<HighlightsResumeStore> {
        com.riffle.core.data.HighlightsResumeStore(get(named(DS_HIGHLIGHTS_RESUME)))
    }
    single<AppUpdatePreferencesStore> {
        com.riffle.core.data.AppUpdatePreferencesStore(get(named(DS_APP_UPDATE)))
    }
    single<ContentCacheSettingsStore> {
        com.riffle.core.data.ContentCacheSettingsStore(get(named(DS_CONTENT_CACHE_SETTINGS)))
    }
}

private val coreDataNetworkModule = module {
    single {
        val cacheDir = File(androidContext().cacheDir, "default-http")
        createDefaultJvmHttpClientPool(cacheDir, DEFAULT_HTTP_CACHE_BYTES)
    }
    single<HttpClient> { get<JvmHttpClientPool>().defaultHttpClient() }
    single<HttpClient>(named(STREAMING_HTTP_CLIENT)) { get<JvmHttpClientPool>().streamingHttpClient() }
    single<HttpClient>(named(WEB_SOURCE_HTTP_CLIENT)) {
        val cacheDir = File(androidContext().cacheDir, "web-source-http")
        createWebSourceHttpClient(
            cacheDirectory = cacheDir,
            cacheSizeBytes = WEB_SOURCE_CACHE_BYTES,
            maxAgeSeconds = WEB_SOURCE_MAX_AGE_SECONDS,
        )
    }

    single { GitHubReleaseApi(get()) }

    // AbsApiClient binds to multiple interfaces
    single { AbsApiClient(get()) }
    single<AbsApi> { get<AbsApiClient>() }
    single<AbsLibraryApi> { get<AbsApiClient>() }
    single<AbsSessionApi> { get<AbsApiClient>() }
    single<AbsServerInfoApi> { get<AbsApiClient>() }
    single<AbsPlaybackApi> { get<AbsApiClient>() }
    single<AbsBookmarkApi> { get<AbsApiClient>() }

    single<AbsFileDownloadApi> { AbsFileDownloadApiClient(get()) }

    // StorytellerApiClient binds to multiple interfaces
    single { StorytellerApiClient(get()) }
    single<StorytellerApi> { get<StorytellerApiClient>() }
    single<StorytellerLibraryApi> { get<StorytellerApiClient>() }

    single { StorytellerBundleApiImpl(get()) }
    single { AudiobookBundleApiImpl(get(named(STREAMING_HTTP_CLIENT))) }
    single<StorytellerPositionApi> { StorytellerPositionApiImpl(get()) }

    single<KomgaServerInfoApi> { KomgaServerInfoApiClient(get()) }
    single { AbsSourceAdapter(get(), get(), get()) }
    single { KomgaSourceAdapter(get()) }
}

private val coreDataRepositoriesModule = module {
    single { CredentialedSourceInstaller(get(), get(), get(), get()) }

    // SourceRepository — uses a lambda to break the circular dependency with ReadaloudSidecarStore
    single<SourceRepository> {
        SourceRepositoryImpl(
            dao = get(),
            tokenStorage = get(),
            serverInfoApi = get(),
            komgaServerInfoApi = get(),
            filesCleaner = get(),
            sidecarCache = { get<ReadaloudSidecarCache>() },
            installer = get(),
            remoteUserIdResolvers = get(),
        )
    }

    // LibraryRepositoryImpl implements LibraryObserver, LibraryMutator, LibraryRefresher
    single {
        LibraryRepositoryImpl(
            catalogRegistry = get(),
            libraryDao = get(),
            libraryItemDao = get(),
            seriesDao = get(),
            collectionDao = get(),
            sourceRepository = get(),
            clock = get(),
            logger = get(),
            dirtyProgressLedger = get(),
        )
    }
    single<LibraryObserver> { get<LibraryRepositoryImpl>() }
    single<LibraryMutator> { get<LibraryRepositoryImpl>() }
    single<LibraryRefresher> { get<LibraryRepositoryImpl>() }

    single<ToReadRepository> { ToReadRepositoryImpl(get(), get(), get()) }
    single<PlaylistsRepository> { PlaylistsRepositoryImpl(get(), get(), get(), get()) }
    single<LocalToReadStore> { LocalToReadStoreImpl(get(named(DS_LOCAL_TO_READ))) }
    single<CrashReportRepository> { CrashReportRepositoryImpl(get(named(CRASH_REPORT_DIR))) }
    single<CrossEpubIndexBuildTrigger> {
        CrossEpubIndexBuilderService(
            catalogRegistry = get(),
            cacheStore = get(named(EPUB_CACHE_STORE)),
            downloadsStore = get(named(EPUB_DOWNLOADS_STORE)),
            store = get(),
            sidecarStore = get(),
            applicationScope = get(),
        )
    }
    single<ReadingSessionRepository> {
        ReadingSessionRepositoryImpl(
            catalogRegistry = get(),
            sourceRepository = get(),
            positionStore = get(),
            audiobookPositionStore = get(),
            readaloudResumeStore = get(),
            libraryItemDao = get(),
            clock = get(),
        )
    }
    single<ConnectivityObserver> { ConnectivityObserverImpl(androidContext(), get()) }
    single<LocalAvailabilityEvents> { LocalAvailabilityEventsImpl() }
    single<TocRepository> { TocRepositoryImpl(get(), get()) }
    single<PublicationMetricsRepository> { PublicationMetricsRepositoryImpl(get(), get()) }
    single<AnnotationsLibraryRepository> { AnnotationsLibraryRepositoryImpl(get(), get()) }

    single<EpubRepository> {
        EpubRepositoryImpl(
            catalogRegistry = get(),
            cacheStore = get(named(EPUB_CACHE_STORE)),
            downloadsStore = get(named(EPUB_DOWNLOADS_STORE)),
            positionStore = get(),
            sourceRepository = get(),
            localAvailabilityEvents = get(),
            contentCacheAccessStore = get(),
        )
    }

    single<PdfRepository> {
        PdfRepositoryImpl(
            catalogRegistry = get(),
            cacheStore = get(named(PDF_CACHE_STORE)),
            downloadsStore = get(named(PDF_DOWNLOADS_STORE)),
            positionStore = get(),
            sourceRepository = get(),
            localAvailabilityEvents = get(),
            contentCacheAccessStore = get(),
        )
    }

    single<CbzRepository> {
        CbzRepositoryImpl(
            catalogRegistry = get(),
            cacheStore = get(named(CBZ_CACHE_STORE)),
            downloadsStore = get(named(CBZ_DOWNLOADS_STORE)),
            positionStore = get(),
            sourceRepository = get(),
            localAvailabilityEvents = get(),
            contentCacheAccessStore = get(),
        )
    }

    single {
        LibraryItemOfflineAvailability(
            epubRepository = get(),
            pdfRepository = get(),
            cbzRepository = get(),
            audiobookDownloadRepository = get(),
            bundleAudiobookSource = get(),
            availabilityChanges = get<LocalAvailabilityEvents>().changes,
            invalidationScope = get<ApplicationScope>().coroutineScope,
        )
    }
}

private val coreDataCatalogModule = module {
    single<Map<SourceType, CatalogFactory>> {
        mapOf(
            SourceType.LOCAL_FILES to LocalFilesCatalogFactory(
                folderDao = get(),
                fileDao = get(),
                fileFolderDao = get(),
                itemDao = get(),
                overrideDao = get(),
            ),
            SourceType.ABS to AbsCatalogFactory(
                libraryApi = get(),
                fileDownloadApi = get(),
                playbackApi = get(),
                sessionApi = get(),
                bookmarkApi = get(),
                serverInfoApi = get(),
                tokenStorage = get(),
                deviceIdStore = get(),
                clock = get(),
            ),
            SourceType.CHITANKA to ChitankaCatalogFactory(
                httpClient = get(named(WEB_SOURCE_HTTP_CLIENT)),
                userAgent = "Riffle/dev (Android) chitanka-source",
            ),
            SourceType.GUTENBERG to GutenbergCatalogFactory(
                sharedHttpClient = get(named(WEB_SOURCE_HTTP_CLIENT)),
                userAgent = "Riffle/dev (Android) gutenberg-source",
            ),
            SourceType.RADIO_ES to RadioEsCatalogFactory(
                httpClient = get(named(WEB_SOURCE_HTTP_CLIENT)),
                userAgent = "Riffle/dev (Android) radio-es-source",
                acceptLanguage = java.util.Locale.getDefault().toLanguageTag(),
            ),
            SourceType.KOMGA to KomgaCatalogFactory(
                httpClient = get(),
                tokenStorage = get(),
                bytesClient = get<JvmHttpClientPool>().fileTransferClient(),
                userAgent = "Riffle/dev (Android) komga-source",
            ),
        )
    }
    single<CatalogRegistry> { DefaultCatalogRegistry(get(), get()) }
}

private val coreDataStreamingAudioModule = module {
    single<AudiobookRepository> { AudiobookRepositoryImpl(get(), get()) }
    single { com.riffle.core.data.AudiobookTrackDownloader(get(named(STREAMING_HTTP_CLIENT)), get()) }
    single<AudiobookDownloadRepository> {
        AudiobookDownloadRepositoryImpl(
            audiobookRepository = get(),
            trackDownloader = get(),
            cacheDir = get(named(AUDIOBOOK_CACHE_DIR)),
            downloadsDir = get(named(AUDIOBOOK_DOWNLOADS_DIR)),
            dispatchers = get(),
            localAvailabilityEvents = get(),
        )
    }
    single<AudiobookCacheRepository> {
        AudiobookCacheRepositoryImpl(
            cacheDir = get(named(AUDIOBOOK_CACHE_DIR)),
            trackDownloader = get(),
            dispatchers = get(),
            localAvailabilityEvents = get(),
        )
    }
    single<ReadaloudLinkRepository> { ReadaloudLinkRepositoryImpl(get()) }

    // ReadaloudReviewRepositoryImpl implements ReadaloudReviewRepository and ReadaloudReviewMutator
    single {
        ReadaloudReviewRepositoryImpl(
            libraryItemDao = get(),
            libraryDao = get(),
            linkDao = get(),
            candidateDao = get(),
            dismissalDao = get(),
        )
    }
    single<ReadaloudReviewRepository> { get<ReadaloudReviewRepositoryImpl>() }
    single<ReadaloudReviewMutator> { get<ReadaloudReviewRepositoryImpl>() }

    single<AudioIdentityResolver> { AudioIdentityResolverImpl(get(), get()) }
    single<AudiobookChapterCacheRepository> { AudiobookChapterCacheRepositoryImpl(get(), get(), get()) }

    single {
        StorytellerSidecarFetcher(
            bundleApi = { url, bookId, token, insecure ->
                get<StorytellerBundleApiImpl>().streamSidecar(url, bookId, token, insecure)
            },
            fullBundleApi = { url, bookId, token, insecure ->
                get<StorytellerBundleApiImpl>().downloadBundle(url, bookId, token, insecure)
            },
            tempDir = { androidContext().cacheDir },
            dispatchers = get(),
        )
    }

    // ReadaloudSidecarStore implements ReadaloudSidecarPrefetcher and ReadaloudSidecarCache
    single {
        ReadaloudSidecarStore(
            androidContext(),
            get<StorytellerSidecarFetcher>(),
            get<SourceRepository>(),
            get<TokenStorage>(),
            get<ApplicationScope>(),
        )
    }
    single<ReadaloudSidecarPrefetcher> { get<ReadaloudSidecarStore>() }
    single<ReadaloudSidecarCache> { get<ReadaloudSidecarStore>() }

    single {
        val ctx = androidContext()
        AudiobookBundleDownloader(
            api = get(),
            targetFileProvider = { sourceId, id ->
                ctx.filesDir.resolve("downloads/epubs").resolve(sourceId).also { it.mkdirs() }
                    .resolve("$id.epub")
            },
            dispatchers = get(),
        )
    }

    single<ReadaloudAudioRepository> {
        ReadaloudAudioRepositoryImpl(
            downloader = get(),
            bundleProbe = get(),
            cacheStore = get(named(EPUB_CACHE_STORE)),
            downloadsStore = get(named(EPUB_DOWNLOADS_STORE)),
            sourceRepository = get(),
            tokenStorage = get(),
            dispatchers = get(),
        )
    }

    single<BundleAudiobookSource> {
        val readaloudLinkRepository = get<ReadaloudLinkRepository>()
        val readaloudAudioRepository = get<ReadaloudAudioRepository>()
        val applicationScope = get<ApplicationScope>()
        StorytellerBundleAudiobookSource(
            readaloudLinkRepository = readaloudLinkRepository,
            readaloudAudioRepository = readaloudAudioRepository,
            linksByAbsItem = OfflineAvailabilitySnapshot(
                applicationScope = applicationScope,
                source = readaloudLinkRepository.observeAll().map(::readaloudLinksByAbsItemKey),
            ),
        )
    }
}

private val coreDataSyncModule = module {
    single<AnnotationSyncConfigStore> { AnnotationSyncConfigStoreImpl(get()) }
    single<DirtyProgressLedger> { RoomDirtyProgressLedger(get(), get()) }
    single<SyncDirtyAnnotationLedger> { RoomDirtyAnnotationLedger(get()) }
    single<ProgressRemoteFactory> {
        CatalogProgressRemoteFactory(
            catalogRegistry = get(),
            libraryItemDao = get(),
            translatorFactory = get(),
            clock = get(),
            annotationSyncConfigStore = get(),
            webDavProgressRemoteFactory = get(),
            sourceRepository = get(),
        )
    }
    single<SyncSourceResolver> { CatalogSyncSourceResolver(get(), get()) }
    single<ItemProgressPuller> {
        ReconcilingItemProgressPuller(
            ebookStore = get(),
            audioStore = get(),
            catalogRegistry = get(),
            remoteFactory = get(),
            locks = get(),
            openTargets = get(),
            uiProgressSink = get(),
        )
    }
    single<ReadaloudLinkReconciler> {
        ReadaloudMatchingService(
            libraryItemDao = get(),
            readaloudLinkDao = get(),
            readaloudCandidateDao = get(),
            readaloudDismissalDao = get(),
            logger = get(),
        )
    }
    single<StorytellerReadaloudCacheSyncer> {
        StorytellerReadaloudSyncer(
            sourceRepository = get(),
            tokenStorage = get(),
            storytellerApi = get(),
            libraryItemDao = get(),
            clock = System::currentTimeMillis,
        )
    }
    single<AudiobookBookmarkSyncStore> { AudiobookBookmarkSyncStoreImpl(get()) }

    single {
        ProgressSweep(
            ledger = get(),
            sourceResolver = get(),
            ebookReconciler = ProgressReconciler(get<ReadingPositionStoreImpl>(), get()),
            audioReconciler = ProgressReconciler(get<AudiobookPositionStoreImpl>(), get()),
            remoteFactory = get(),
            locks = get(),
            openTargets = get(),
            bookmarkLedger = object : DirtyBookmarkLedger {
                override suspend fun serversWithDirty() =
                    get<com.riffle.core.database.AudiobookBookmarkDao>().sourcesWithDirtyRows()
                override suspend fun dirtyItems(sourceId: String) =
                    get<com.riffle.core.database.AudiobookBookmarkDao>()
                        .dirtyForSource(sourceId).map { it.itemId }.distinct()
            },
            bookmarkReconcile = BookmarkReconcile { sourceId, itemId ->
                get<AudiobookBookmarkReconciler>().reconcile(sourceId, itemId)
            },
            remoteIndex = get(),
            postSweepMaterializer = get(),
        )
    }

    single { ReconcileLocks() }
    single<com.riffle.core.sync.AnnotationLockPort> { get<ReconcileLocks>() }
    single { OpenReconcileTargets() }
    single { AnnotationSyncStatusStore() }

    single {
        AudiobookBookmarkReconciler(
            store = get(),
            sourceResolver = get(),
            clock = get(),
            random = get(),
        )
    }

    single {
        StorytellerPositionSyncController(
            api = get(),
            positionStore = get(),
            sourceRepository = get(),
            tokenStorage = get(),
        )
    }

    single { AnnotationMergeService() }

    single {
        WebDavAnnotationSyncTargetFactory(get(), get())
    }

    single {
        WebDavProgressRemoteFactory(get(), get())
    }

    single {
        WebDavProgressEnumerator(get(), get())
    }

    single<RemoteProgressIndex> {
        CatalogRemoteProgressIndex(
            sourceRepository = get(),
            annotationSyncConfigStore = get(),
            enumerator = get(),
            readingPositionDao = get(),
            audiobookPositionDao = get(),
            libraryItemDao = get(),
            clock = get(),
        )
    }

    single {
        val dispatchers = get<DispatcherProvider>()
        AnnotationSyncTargetHolder(
            configStore = get(),
            webDavFactory = get(),
            absBookmarkFactory = get(),
            sourceRepository = get(),
            scope = CoroutineScope(SupervisorJob() + dispatchers.io),
        )
    }

    single {
        val dispatchers = get<DispatcherProvider>()
        val holder = get<AnnotationSyncTargetHolder>()
        AnnotationSyncController(
            targetProvider = { holder.current() },
            mergeService = get(),
            annotationDao = get(),
            deviceIdStore = get(),
            deviceLabelResolver = get(),
            scope = CoroutineScope(SupervisorJob() + dispatchers.io),
            statusStore = get(),
            sweepEnqueuer = get(),
            usernameProvider = { sid -> get<SourceRepository>().getById(sid)?.username },
            bookTitleProvider = { sid, itemId ->
                get<com.riffle.core.database.LibraryItemDao>().getById(sid, itemId)
                    ?.title?.takeIf { it.isNotBlank() }
            },
            locks = get(),
            sentinelWriter = get(),
        )
    }

    single {
        val holder = get<AnnotationSyncTargetHolder>()
        AnnotationSweep(
            targetProvider = { holder.current() },
            annotationDao = get(),
            deviceIdStore = get(),
            deviceLabelResolver = get(),
            sourceRepository = get(),
            statusStore = get(),
            bookTitleProvider = { sid, itemId ->
                get<com.riffle.core.database.LibraryItemDao>().getById(sid, itemId)
                    ?.title?.takeIf { it.isNotBlank() }
            },
            dirtyLedger = get(),
            locks = get(),
            sentinelWriter = get(),
        )
    }

    single {
        val holder = get<AnnotationSyncTargetHolder>()
        AnnotationSyncMaintenance(targetProvider = { holder.current() })
    }

    single {
        DeviceMetaSentinelWriter(
            deviceIdStore = get(),
            deviceLabelResolver = get(),
            usernameProvider = { sid -> get<SourceRepository>().getById(sid)?.username },
        )
    }

    single { com.riffle.core.data.websource.WebSourceLibraryItemUpserter(get()) }
    single {
        LibraryItemUiProgressSink(
            libraryItemDao = get(),
            sourceRepository = get(),
            catalogRegistry = get(),
            upserter = get(),
        )
    }
    single {
        WebSourceLibraryItemMaterializer(
            readingPositionDao = get(),
            audiobookPositionDao = get(),
            libraryItemDao = get(),
            sourceRepository = get(),
            catalogRegistry = get(),
            remoteFactory = get(),
            upserter = get(),
        )
    }

    single { AbsBookmarkAnnotationSyncTargetFactory(get(), get()) }
    single<RemoteUserIdResolver>(named("abs")) { AbsRemoteUserIdResolver(get()) }
    single<RemoteUserIdResolver>(named("komga")) { KomgaRemoteUserIdResolver(get()) }
    single<Map<SourceType, RemoteUserIdResolver>> {
        mapOf(
            SourceType.ABS to get(named("abs")),
            SourceType.KOMGA to get(named("komga")),
        )
    }
}

private val coreDataDeveloperModule = module {
    single(named(DS_DEVELOPER_OPTIONS)) { androidContext().developerOptionsDataStore }
    single<PatStore> { AndroidPatStore(androidContext()) }
    single<DeveloperOptionsRepository> {
        DeveloperOptionsRepositoryImpl(
            dataStore = get(named(DS_DEVELOPER_OPTIONS)),
            patStore = get(),
        )
    }
}

private val coreDataPanelModule = module {
    single(named(DS_PANEL_VIEW)) { androidContext().panelViewPreferencesDataStore }

    single<PageImageDecoder> { AndroidPageImageDecoder() }
    single<PanelMaskService> { AndroidPanelMaskServiceImpl(get(), get(), get()) }
    single<PanelViewPreferencesStore> { com.riffle.core.data.PanelViewPreferencesStoreImpl(get(named(DS_PANEL_VIEW))) }
    single<BookComicFormattingPreferencesStore> { com.riffle.core.data.BookComicFormattingPreferencesStoreImpl(get()) }

    single<PanelStore> { JsonPanelStore(File(androidContext().filesDir, "comic-panels").also { it.mkdirs() }) }
    single { PanelDetectionConfig() }

    single<PanelReportRepository> {
        val developerOptionsRepository = get<DeveloperOptionsRepository>()
        val httpClient = get<HttpClient>()
        object : PanelReportRepository {
            override suspend fun submit(report: PanelDetectionReport, maskPng: ByteArray): Result<String> {
                val pat = developerOptionsRepository.getGithubPat()
                    ?: return Result.failure(IllegalStateException("No GitHub PAT configured"))
                return GitHubPanelReportRepository(pat = pat, client = httpClient).submit(report, maskPng)
            }
        }
    }

    single {
        PanelOrchestrator(config = get(), store = get(), decoder = get())
    }
    single<PanelEngine> { get<PanelOrchestrator>() }
}

private val coreDataMiscModule = module {
    // AppUpdate
    single<AppUpdateRepository> {
        com.riffle.core.data.AppUpdateRepositoryImpl(
            context = androidContext(),
            releaseApi = get(),
            installer = get(),
            dispatchers = get(),
        )
    }

    // LocalFiles
    single<FolderWalker> { SafFolderWalker(androidContext()) }
    single<CopyInService> { AndroidCopyInService(androidContext()) }

    // CredentialedAuthenticator (Map<SourceType, SourceAdapter>)
    single<Map<SourceType, SourceAdapter>> {
        mapOf(
            SourceType.ABS to get<AbsSourceAdapter>(),
            SourceType.KOMGA to get<KomgaSourceAdapter>(),
        )
    }

    // WebSourceDescriptors — use the canonical all-set maintained by WebSourceDescriptors
    single<WebSourceRegistry> { WebSourceRegistry(WebSourceDescriptors.all) }

    // Dictionary
    single {
        WordLookupRepositoryImpl(
            dictionaryPackDao = get(),
            lookupHistoryDao = get(),
            packSqliteStore = get(),
            dispatchers = get(),
            clock = get(),
        )
    }
    single<DictionaryRepository> { get<WordLookupRepositoryImpl>() }
    single<PackStore> { get<WordLookupRepositoryImpl>() }

    single { DictionaryPackSqliteStore(androidContext().filesDir) }
    single {
        PackDownloader(
            filesDir = androidContext().filesDir,
            httpClient = get<JvmHttpClientPool>().fileTransferClient(),
            dictionaryPackDao = get(),
            clock = get(),
            converter = KaikkiJsonlToSqliteConverter(),
        )
    }
}

fun coreDataKoinModules(): List<Module> = listOf(
    coreDataDatabaseModule,
    coreDataLocalStoreModule,
    coreDataPreferencesModule,
    coreDataNetworkModule,
    coreDataRepositoriesModule,
    coreDataCatalogModule,
    coreDataStreamingAudioModule,
    coreDataSyncModule,
    coreDataDeveloperModule,
    coreDataPanelModule,
    coreDataMiscModule,
)
