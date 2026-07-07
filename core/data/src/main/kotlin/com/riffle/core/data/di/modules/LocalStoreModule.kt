package com.riffle.core.data.di.modules

import android.content.Context
import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.data.AudiobookBookmarkStoreImpl
import com.riffle.core.data.AudiobookPositionStoreImpl
import com.riffle.core.data.CrossEpubIndexStoreImpl
import com.riffle.core.data.DeviceIdStoreImpl
import com.riffle.core.data.DownloadsRepositoryImpl
import com.riffle.core.data.EncryptedKeyValueStore
import com.riffle.core.data.KeystoreEncryptedKeyValueStore
import com.riffle.core.data.KeystoreTokenStorage
import com.riffle.core.data.LocalStoreImpl
import com.riffle.core.data.LocalStoreMigrator
import com.riffle.core.data.ReadaloudResumeStoreImpl
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.SourceFilesCleanerImpl
import com.riffle.core.data.di.AudiobookDownloadsDir
import com.riffle.core.data.di.CrashReportDir
import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.data.di.EpubDownloadsStore
import com.riffle.core.data.di.PdfCacheStore
import com.riffle.core.data.di.PdfDownloadsStore
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceFilesCleaner
import com.riffle.core.domain.TokenStorage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalStoreModule {

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: KeystoreTokenStorage): TokenStorage

    @Binds
    @Singleton
    abstract fun bindEncryptedKeyValueStore(impl: KeystoreEncryptedKeyValueStore): EncryptedKeyValueStore

    @Binds
    @Singleton
    abstract fun bindAnnotationStore(impl: AnnotationStoreImpl): AnnotationStore

    @Binds
    @Singleton
    abstract fun bindCrossEpubIndexStore(impl: CrossEpubIndexStoreImpl): CrossEpubIndexStore

    @Binds
    @Singleton
    abstract fun bindReadingPositionStore(impl: ReadingPositionStoreImpl): ReadingPositionStore

    @Binds
    @Singleton
    abstract fun bindAudiobookPositionStore(impl: AudiobookPositionStoreImpl): AudiobookPositionStore

    @Binds
    @Singleton
    abstract fun bindAudiobookBookmarkStore(impl: AudiobookBookmarkStoreImpl): AudiobookBookmarkStore

    @Binds
    @Singleton
    abstract fun bindReadaloudResumeStore(impl: ReadaloudResumeStoreImpl): ReadaloudResumeStore

    @Binds
    @Singleton
    abstract fun bindDeviceIdStore(impl: DeviceIdStoreImpl): DeviceIdStore

    @Binds
    @Singleton
    abstract fun bindDeviceLabelStore(impl: com.riffle.core.data.DeviceLabelStoreImpl): com.riffle.core.domain.DeviceLabelStore

    @Binds
    @Singleton
    abstract fun bindDeviceLabelResolver(impl: com.riffle.core.data.AndroidDeviceLabelResolver): com.riffle.core.domain.DeviceLabelResolver

    companion object {
        @Provides
        @Singleton
        @CrashReportDir
        fun provideCrashReportDir(@ApplicationContext context: Context): File =
            File(context.filesDir, "crash_reports").apply { mkdirs() }

        @Provides
        @Singleton
        @EpubCacheStore
        fun provideEpubCacheStore(@ApplicationContext context: Context, dispatchers: com.riffle.core.domain.DispatcherProvider): LocalStore =
            LocalStoreImpl(context.cacheDir.resolve("epubs").also { it.mkdirs() }, ".epub", dispatchers)

        @Provides
        @Singleton
        @EpubDownloadsStore
        fun provideEpubDownloadsStore(@ApplicationContext context: Context, dispatchers: com.riffle.core.domain.DispatcherProvider): LocalStore =
            LocalStoreImpl(context.filesDir.resolve("downloads/epubs").also { it.mkdirs() }, ".epub", dispatchers)

        @Provides
        @Singleton
        @AudiobookDownloadsDir
        fun provideAudiobookDownloadsDir(@ApplicationContext context: Context): File =
            context.filesDir.resolve("downloads/audiobooks").also { it.mkdirs() }

        @Provides
        @Singleton
        @PdfCacheStore
        fun providePdfCacheStore(@ApplicationContext context: Context, dispatchers: com.riffle.core.domain.DispatcherProvider): LocalStore =
            LocalStoreImpl(context.cacheDir.resolve("pdfs").also { it.mkdirs() }, ".pdf", dispatchers)

        @Provides
        @Singleton
        @PdfDownloadsStore
        fun providePdfDownloadsStore(@ApplicationContext context: Context, dispatchers: com.riffle.core.domain.DispatcherProvider): LocalStore =
            LocalStoreImpl(context.filesDir.resolve("downloads/pdfs").also { it.mkdirs() }, ".pdf", dispatchers)

        // One-time relocation of legacy flat files into per-Source subdirectories (ADR 0025).
        @Provides
        @Singleton
        fun provideLocalStoreMigrator(
            @ApplicationContext context: Context,
            libraryItemDao: LibraryItemDao,
            dispatchers: com.riffle.core.domain.DispatcherProvider,
        ): LocalStoreMigrator =
            LocalStoreMigrator(
                stores = listOf(
                    context.cacheDir.resolve("epubs") to ".epub",
                    context.filesDir.resolve("downloads/epubs") to ".epub",
                    context.cacheDir.resolve("pdfs") to ".pdf",
                    context.filesDir.resolve("downloads/pdfs") to ".pdf",
                ),
                resolveServerId = { itemId -> libraryItemDao.findSourceIdForItem(itemId) },
                dispatchers = dispatchers,
            )

        @Provides
        @Singleton
        fun provideDownloadsRepository(
            @EpubCacheStore epubCacheStore: LocalStore,
            @EpubDownloadsStore epubDownloadsStore: LocalStore,
            @PdfCacheStore pdfCacheStore: LocalStore,
            @PdfDownloadsStore pdfDownloadsStore: LocalStore,
        ): DownloadsRepository = DownloadsRepositoryImpl(epubCacheStore, epubDownloadsStore, pdfCacheStore, pdfDownloadsStore)

        @Provides
        @Singleton
        fun provideSourceFilesCleaner(
            @EpubCacheStore epubCacheStore: LocalStore,
            @EpubDownloadsStore epubDownloadsStore: LocalStore,
            @PdfCacheStore pdfCacheStore: LocalStore,
            @PdfDownloadsStore pdfDownloadsStore: LocalStore,
            @AudiobookDownloadsDir audiobookDownloadsDir: File,
            dispatchers: com.riffle.core.domain.DispatcherProvider,
        ): SourceFilesCleaner = SourceFilesCleanerImpl(
            stores = listOf(epubCacheStore, epubDownloadsStore, pdfCacheStore, pdfDownloadsStore),
            audiobookDownloadsDir = audiobookDownloadsDir,
            dispatchers = dispatchers,
        )
    }
}
