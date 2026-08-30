package com.riffle.app.di

import com.riffle.app.feature.library.BookImportManager
import com.riffle.app.feature.library.DownloadManager
import com.riffle.app.feature.library.ExtractPdfPageCountUseCase
import com.riffle.app.feature.library.FetchAudiobookChaptersUseCase
import com.riffle.app.feature.library.LibraryTabVisibilityObserver
import com.riffle.app.feature.reader.ExtractEpubTocUseCase
import com.riffle.app.feature.reader.readaloud.ReadaloudOfflineDownloader
import com.riffle.app.feature.server.AddSourceViewModel
import com.riffle.app.playback.NowPlayingNavigator
import com.riffle.app.playback.NowPlayingStore
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.Clock
import com.riffle.core.data.AnnotationSyncMaintenance
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.data.CrossEpubIndexBuildTrigger
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.ReadaloudMatchingService
import com.riffle.core.data.ReadaloudSidecarPrefetcher
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.credentialed.CredentialedAuthenticator
import com.riffle.core.data.localfiles.CopyCoverImageUseCase
import com.riffle.core.data.localfiles.LocalFilesFolderHealthChecker
import com.riffle.core.data.localfiles.LocalFilesFolderRepository
import com.riffle.core.data.localfiles.LocalFilesScanner
import com.riffle.core.data.localfiles.LocalFilesSourceInstaller
import com.riffle.core.data.localfiles.SaveLocalFileMetadataOverrideUseCase
import com.riffle.core.data.websource.SingletonWebSourceInstaller
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.DeviceLabelStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import com.riffle.core.domain.usecase.MarkReadAcrossDimensions
import com.riffle.core.domain.usecase.ReadaloudReviewActions
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.domain.usecase.RefreshCollections
import com.riffle.core.domain.usecase.RefreshLibraries
import com.riffle.core.domain.usecase.RefreshLibraryItems
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.logging.InMemoryLogBuffer
import com.riffle.core.models.SourceType
import com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory
import com.riffle.core.sync.AnnotationSyncStatusStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Named

/**
 * Hilt entry point that exposes all singleton/factory bindings needed by Koin ViewModel modules
 * during the Hilt→Koin transition. Remove when #737 registers all core:data bindings in Koin
 * natively.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KoinBridgeEntryPoint {
    fun libraryObserver(): LibraryObserver
    fun refreshLibraryItems(): RefreshLibraryItems
    fun refreshSeries(): RefreshSeries
    fun refreshCollections(): RefreshCollections
    fun refreshLibraries(): RefreshLibraries
    fun sourceRepository(): SourceRepository
    fun tokenStorage(): TokenStorage
    fun libraryItemOfflineAvailability(): LibraryItemOfflineAvailability
    fun connectivityObserver(): ConnectivityObserver
    fun toReadRepository(): ToReadRepository
    fun playlistsRepository(): PlaylistsRepository
    fun readaloudLinkRepository(): ReadaloudLinkRepository
    fun coverGridDensityStore(): CoverGridDensityStore
    fun libraryFilterPreferencesStore(): LibraryFilterPreferencesStore
    fun annotationStore(): AnnotationStore
    fun audiobookBookmarkStore(): AudiobookBookmarkStore
    fun annotationsLibraryRepository(): AnnotationsLibraryRepository
    fun dispatchers(): DispatcherProvider
    fun libraryVisibilityPreferencesStore(): LibraryVisibilityPreferencesStore
    fun lastOpenedLibraryStore(): LastOpenedLibraryStore
    fun libraryOrderPreferencesStore(): LibraryOrderPreferencesStore
    fun catalogRegistry(): CatalogRegistry
    fun nowPlayingNavigator(): NowPlayingNavigator
    fun nowPlayingStore(): NowPlayingStore
    fun recordItemOpened(): RecordItemOpened
    fun updateReadingProgress(): UpdateReadingProgress
    fun markReadAcrossDimensions(): MarkReadAcrossDimensions
    fun epubRepository(): EpubRepository
    fun ebookCfiTranslatorFactory(): EbookCfiTranslatorFactory
    fun audiobookPositionStore(): AudiobookPositionStore
    fun pdfRepository(): PdfRepository
    fun cbzRepository(): CbzRepository
    fun audiobookDownloadRepository(): AudiobookDownloadRepository
    fun audiobookCacheRepository(): AudiobookCacheRepository
    fun localAvailabilityEvents(): LocalAvailabilityEvents
    fun readaloudAudioRepository(): ReadaloudAudioRepository
    fun readaloudOfflineDownloader(): ReadaloudOfflineDownloader
    fun crossEpubIndexBuildTrigger(): CrossEpubIndexBuildTrigger
    fun readaloudSidecarPrefetcher(): ReadaloudSidecarPrefetcher
    fun extractEpubTocUseCase(): ExtractEpubTocUseCase
    fun extractPdfPageCountUseCase(): ExtractPdfPageCountUseCase
    fun fetchAudiobookChaptersUseCase(): FetchAudiobookChaptersUseCase
    fun libraryRefresher(): LibraryRefresher
    fun saveLocalFileMetadataOverride(): SaveLocalFileMetadataOverrideUseCase
    fun copyCoverImage(): CopyCoverImageUseCase
    fun readingSpeedStore(): ReadingSpeedStore
    fun webSourceLibraryItemUpserter(): WebSourceLibraryItemUpserter
    fun webSourceItemGate(): WebSourceItemGate
    fun downloadManager(): DownloadManager
    fun bookImportManager(): BookImportManager
    fun downloadsRepository(): DownloadsRepository
    fun readaloudSidecarStore(): ReadaloudSidecarStore
    fun contentCacheSettingsStore(): ContentCacheSettingsStore
    fun crashReportRepository(): CrashReportRepository
    fun formattingPreferencesStore(): FormattingPreferencesStore
    fun wakeLockPreferencesStore(): WakeLockPreferencesStore
    fun volumeKeyPreferencesStore(): VolumeKeyPreferencesStore
    fun listeningPreferencesStore(): ListeningPreferencesStore
    fun appThemeStore(): AppThemeStore
    fun readaloudReviewRepository(): ReadaloudReviewRepository
    fun appUpdateRepository(): AppUpdateRepository
    fun appUpdatePreferencesStore(): AppUpdatePreferencesStore
    fun readaloudPreferencesStore(): ReadaloudPreferencesStore
    fun localFilesFolderDao(): LocalFilesFolderDao
    fun localFilesFolderRepository(): LocalFilesFolderRepository
    fun localFilesScanner(): LocalFilesScanner
    fun localFilesSourceInstaller(): LocalFilesSourceInstaller
    fun localFilesFolderHealthChecker(): LocalFilesFolderHealthChecker
    fun comicFormattingPreferencesStore(): ComicFormattingPreferencesStore
    fun developerOptionsRepository(): DeveloperOptionsRepository
    fun annotationSyncConfigStore(): AnnotationSyncConfigStore
    fun annotationSyncStatusStore(): AnnotationSyncStatusStore
    fun annotationDao(): AnnotationDao
    fun inMemoryLogBuffer(): InMemoryLogBuffer
    fun annotationSyncMaintenance(): AnnotationSyncMaintenance
    fun deviceIdStore(): DeviceIdStore
    fun deviceLabelStore(): DeviceLabelStore
    fun deviceLabelResolver(): DeviceLabelResolver
    fun annotationSweepEnqueuer(): AnnotationSweepEnqueuer
    fun readaloudReviewActions(): ReadaloudReviewActions
    fun webDavAnnotationSyncTargetFactory(): WebDavAnnotationSyncTargetFactory
    fun storytellerReadaloudSyncer(): StorytellerReadaloudSyncer
    fun readaloudMatchingService(): ReadaloudMatchingService
    fun authenticators(): Map<SourceType, @JvmSuppressWildcards CredentialedAuthenticator>
    fun clock(): Clock
    @Named(AddSourceViewModel.WEBDAV_BANNER_TICKER) fun webdavBannerTicker(): Flow<Unit>
    fun singletonWebSourceInstaller(): SingletonWebSourceInstaller
    fun libraryTabVisibilityObserver(): LibraryTabVisibilityObserver
}
