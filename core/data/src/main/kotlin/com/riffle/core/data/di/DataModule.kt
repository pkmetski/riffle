package com.riffle.core.data.di

import com.riffle.core.data.di.modules.LocalStoreModule
import com.riffle.core.data.di.modules.NetworkModule
import com.riffle.core.data.di.modules.PreferencesModule
import com.riffle.core.data.di.modules.RepositoriesModule
import com.riffle.core.data.di.modules.StreamingAudioModule
import com.riffle.core.data.di.modules.SyncModule
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CrashReportDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FormattingPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibraryVisibilityPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibraryOrderPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LastOpenedLibraryDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WakeLockPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VolumeKeyPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppThemePreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CoverGridDensityDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceIdDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceLabelDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReadaloudPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HighlightColorPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReadingSpeedDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HighlightsResumePreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ListeningPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpubCacheStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpubDownloadsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AudiobookDownloadsDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PdfCacheStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PdfDownloadsStore

@Module(
    includes = [
        NetworkModule::class,
        PreferencesModule::class,
        LocalStoreModule::class,
        StreamingAudioModule::class,
        RepositoriesModule::class,
        SyncModule::class,
    ],
)
@InstallIn(SingletonComponent::class)
object DataModule
