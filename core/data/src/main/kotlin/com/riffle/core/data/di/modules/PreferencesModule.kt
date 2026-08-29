package com.riffle.core.data.di.modules

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.riffle.core.data.AudioPlaybackPreferencesStoreImpl
import com.riffle.core.data.BookFormattingPreferencesStoreImpl
import com.riffle.core.data.ComicFormattingPreferencesStoreImpl
import com.riffle.core.data.ContentCacheAccessStoreImpl
import com.riffle.core.data.FormattingPreferencesStoreImpl
import com.riffle.core.data.FormattingPreferencesStoreProviderImpl
import com.riffle.core.data.LastOpenedLibraryStoreImpl
import com.riffle.core.data.LibraryFilterPreferencesStoreImpl
import com.riffle.core.data.LibraryOrderPreferencesStoreImpl
import com.riffle.core.data.LibraryVisibilityPreferencesStoreImpl
import com.riffle.core.data.ListeningPreferencesStoreImpl
import com.riffle.core.data.VolumeKeyPreferencesStoreImpl
import com.riffle.core.data.di.AppThemePreferencesDataStore
import com.riffle.core.data.di.AppUpdatePreferencesDataStore
import com.riffle.core.data.di.ComicFormattingPreferencesDataStore
import com.riffle.core.data.di.ContentCacheAccessDataStore
import com.riffle.core.data.di.ContentCacheSettingsDataStore
import com.riffle.core.data.di.CoverGridDensityDataStore
import com.riffle.core.data.di.DeviceIdDataStore
import com.riffle.core.data.di.DeviceLabelDataStore
import com.riffle.core.data.di.FormattingPreferencesDataStore
import com.riffle.core.data.di.HighlightsFormattingPreferencesDataStore
import com.riffle.core.data.di.HighlightColorPreferencesDataStore
import com.riffle.core.data.di.HighlightsResumePreferencesDataStore
import com.riffle.core.data.di.LastOpenedLibraryDataStore
import com.riffle.core.data.di.LibraryFilterPreferencesDataStore
import com.riffle.core.data.di.LibraryOrderPreferencesDataStore
import com.riffle.core.data.di.LibraryVisibilityPreferencesDataStore
import com.riffle.core.data.di.ListeningPreferencesDataStore
import com.riffle.core.data.di.LocalToReadDataStore
import com.riffle.core.data.di.ReadaloudPreferencesDataStore
import com.riffle.core.data.di.ReadingSpeedDataStore
import com.riffle.core.data.di.VolumeKeyPreferencesDataStore
import com.riffle.core.data.di.WakeLockPreferencesDataStore
import com.riffle.core.data.di.appThemePreferencesDataStore
import com.riffle.core.data.di.appUpdatePreferencesDataStore
import com.riffle.core.data.di.comicFormattingPreferencesDataStore
import com.riffle.core.data.di.contentCacheAccessDataStore
import com.riffle.core.data.di.contentCacheSettingsDataStore
import com.riffle.core.data.di.coverGridDensityDataStore
import com.riffle.core.data.di.deviceIdDataStore
import com.riffle.core.data.di.deviceLabelDataStore
import com.riffle.core.data.di.formattingPreferencesDataStore
import com.riffle.core.data.di.formattingPreferencesHighlightsDataStore
import com.riffle.core.data.di.highlightColorPreferencesDataStore
import com.riffle.core.data.di.highlightsResumePreferencesDataStore
import com.riffle.core.data.di.lastOpenedLibraryDataStore
import com.riffle.core.data.di.libraryFilterPreferencesDataStore
import com.riffle.core.data.di.libraryOrderPreferencesDataStore
import com.riffle.core.data.di.libraryVisibilityPreferencesDataStore
import com.riffle.core.data.di.listeningPreferencesDataStore
import com.riffle.core.data.di.localToReadDataStore
import com.riffle.core.data.di.readaloudPreferencesDataStore
import com.riffle.core.data.di.readingSpeedDataStore
import com.riffle.core.data.di.volumeKeyPreferencesDataStore
import com.riffle.core.data.di.wakeLockPreferencesDataStore
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferencesStoreProvider
import com.riffle.core.domain.HighlightColorPreferencesStore
import com.riffle.core.domain.HighlightsResumeStore
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.data.AppThemeStore as createAppThemeStore
import com.riffle.core.data.AppUpdatePreferencesStore as createAppUpdatePreferencesStore
import com.riffle.core.data.ContentCacheSettingsStore as createContentCacheSettingsStore
import com.riffle.core.data.CoverGridDensityStoreImpl
import com.riffle.core.data.EmphasisPreferencesStore as createEmphasisPreferencesStore
import com.riffle.core.data.HighlightColorPreferencesStore as createHighlightColorPreferencesStore
import com.riffle.core.data.HighlightsResumeStore as createHighlightsResumeStore
import com.riffle.core.data.ReadaloudPreferencesStore as createReadaloudPreferencesStore
import com.riffle.core.data.ReadingSpeedStore as createReadingSpeedStore
import com.riffle.core.data.WakeLockPreferencesStore as createWakeLockPreferencesStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesModule {

    @Binds
    @Singleton
    abstract fun bindFormattingPreferencesStore(impl: FormattingPreferencesStoreImpl): FormattingPreferencesStore

    @Binds
    @Singleton
    abstract fun bindBookFormattingPreferencesStore(impl: BookFormattingPreferencesStoreImpl): BookFormattingPreferencesStore

    @Binds
    @Singleton
    abstract fun bindAudioPlaybackPreferencesStore(impl: AudioPlaybackPreferencesStoreImpl): AudioPlaybackPreferencesStore

    @Binds
    @Singleton
    abstract fun bindLibraryVisibilityPreferencesStore(impl: LibraryVisibilityPreferencesStoreImpl): LibraryVisibilityPreferencesStore

    @Binds
    @Singleton
    abstract fun bindLibraryFilterPreferencesStore(impl: LibraryFilterPreferencesStoreImpl): LibraryFilterPreferencesStore

    @Binds
    @Singleton
    abstract fun bindLibraryOrderPreferencesStore(impl: LibraryOrderPreferencesStoreImpl): LibraryOrderPreferencesStore

    @Binds
    @Singleton
    abstract fun bindLastOpenedLibraryStore(impl: LastOpenedLibraryStoreImpl): LastOpenedLibraryStore

    @Binds
    @Singleton
    abstract fun bindListeningPreferencesStore(impl: ListeningPreferencesStoreImpl): ListeningPreferencesStore

    @Binds
    @Singleton
    abstract fun bindVolumeKeyPreferencesStore(impl: VolumeKeyPreferencesStoreImpl): VolumeKeyPreferencesStore

    @Binds
    @Singleton
    abstract fun bindContentCacheAccessStore(impl: ContentCacheAccessStoreImpl): ContentCacheAccessStore

    @Binds
    @Singleton
    abstract fun bindComicFormattingPreferencesStore(
        impl: ComicFormattingPreferencesStoreImpl,
    ): ComicFormattingPreferencesStore

    companion object {
        @Provides @Singleton @FormattingPreferencesDataStore
        fun provideFormattingPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.formattingPreferencesDataStore

        @Provides @Singleton @HighlightsFormattingPreferencesDataStore
        fun provideHighlightsFormattingPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.formattingPreferencesHighlightsDataStore

        // FormattingPreferencesStoreProvider gives [FormattingSession] access to the global
        // formatting-preferences store. Both the full-book reader and the elided (annotations)
        // reader share the same store so display settings propagate to both views automatically.
        @Provides
        @Singleton
        fun provideFormattingPreferencesStoreProvider(
            fullBook: FormattingPreferencesStoreImpl,
        ): FormattingPreferencesStoreProvider = FormattingPreferencesStoreProviderImpl(
            fullBook = fullBook,
        )

        @Provides @Singleton @LibraryVisibilityPreferencesDataStore
        fun provideLibraryVisibilityPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.libraryVisibilityPreferencesDataStore

        @Provides @Singleton @LibraryFilterPreferencesDataStore
        fun provideLibraryFilterPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.libraryFilterPreferencesDataStore

        @Provides @Singleton @LocalToReadDataStore
        fun provideLocalToReadDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.localToReadDataStore

        @Provides @Singleton @LibraryOrderPreferencesDataStore
        fun provideLibraryOrderPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.libraryOrderPreferencesDataStore

        @Provides @Singleton @LastOpenedLibraryDataStore
        fun provideLastOpenedLibraryDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.lastOpenedLibraryDataStore

        @Provides @Singleton @WakeLockPreferencesDataStore
        fun provideWakeLockPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.wakeLockPreferencesDataStore

        @Provides @Singleton @ListeningPreferencesDataStore
        fun provideListeningPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.listeningPreferencesDataStore

        @Provides @Singleton @VolumeKeyPreferencesDataStore
        fun provideVolumeKeyPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.volumeKeyPreferencesDataStore

        @Provides @Singleton @AppThemePreferencesDataStore
        fun provideAppThemePreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.appThemePreferencesDataStore

        @Provides @Singleton @CoverGridDensityDataStore
        fun provideCoverGridDensityDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.coverGridDensityDataStore

        @Provides @Singleton @DeviceIdDataStore
        fun provideDeviceIdDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.deviceIdDataStore

        @Provides @Singleton @DeviceLabelDataStore
        fun provideDeviceLabelDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.deviceLabelDataStore

        @Provides @Singleton @ReadaloudPreferencesDataStore
        fun provideReadaloudPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.readaloudPreferencesDataStore

        @Provides @Singleton @HighlightColorPreferencesDataStore
        fun provideHighlightColorPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.highlightColorPreferencesDataStore

        @Provides @Singleton @ReadingSpeedDataStore
        fun provideReadingSpeedDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.readingSpeedDataStore

        @Provides @Singleton @HighlightsResumePreferencesDataStore
        fun provideHighlightsResumePreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.highlightsResumePreferencesDataStore

        @Provides @Singleton @ContentCacheSettingsDataStore
        fun provideContentCacheSettingsDataStore(@ApplicationContext c: Context): DataStore<Preferences> =
            c.contentCacheSettingsDataStore

        @Provides @Singleton @ContentCacheAccessDataStore
        fun provideContentCacheAccessDataStore(@ApplicationContext c: Context): DataStore<Preferences> =
            c.contentCacheAccessDataStore

        // Single-key DataStore<Preferences> wrappers — see PreferenceStoreFactories.kt.
        @Provides
        @Singleton
        fun provideAppThemeStore(
            @AppThemePreferencesDataStore dataStore: DataStore<Preferences>,
        ): AppThemeStore = createAppThemeStore(dataStore)

        @Provides
        @Singleton
        fun provideCoverGridDensityStore(
            @CoverGridDensityDataStore dataStore: DataStore<Preferences>,
            dao: com.riffle.core.database.CoverGridScaleDao,
        ): CoverGridDensityStore = CoverGridDensityStoreImpl(dataStore, dao)

        @Provides
        @Singleton
        fun provideReadingSpeedStore(
            @ReadingSpeedDataStore dataStore: DataStore<Preferences>,
        ): ReadingSpeedStore = createReadingSpeedStore(dataStore)

        @Provides
        @Singleton
        fun provideWakeLockPreferencesStore(
            @WakeLockPreferencesDataStore dataStore: DataStore<Preferences>,
        ): WakeLockPreferencesStore = createWakeLockPreferencesStore(dataStore)

        @Provides
        @Singleton
        fun provideReadaloudPreferencesStore(
            @ReadaloudPreferencesDataStore dataStore: DataStore<Preferences>,
        ): ReadaloudPreferencesStore = createReadaloudPreferencesStore(dataStore)

        @Provides
        @Singleton
        fun provideHighlightColorPreferencesStore(
            @HighlightColorPreferencesDataStore dataStore: DataStore<Preferences>,
        ): HighlightColorPreferencesStore = createHighlightColorPreferencesStore(dataStore)

        // ADR 0056: last-used emphasis styles per book. Piggybacks on the highlight-color
        // DataStore file — the keys are namespaced (`last_used_emphasis_styles:…` vs
        // `last_used_highlight_color:…`) so there's no collision, and colocating them keeps the
        // set of "picker default" preferences in one file per user.
        @Provides
        @Singleton
        fun provideEmphasisPreferencesStore(
            @HighlightColorPreferencesDataStore dataStore: DataStore<Preferences>,
        ): com.riffle.core.domain.EmphasisPreferencesStore = createEmphasisPreferencesStore(dataStore)

        @Provides
        @Singleton
        fun provideHighlightsResumeStore(
            @HighlightsResumePreferencesDataStore dataStore: DataStore<Preferences>,
        ): HighlightsResumeStore = createHighlightsResumeStore(dataStore)

        @Provides @Singleton @AppUpdatePreferencesDataStore
        fun provideAppUpdatePreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> =
            c.appUpdatePreferencesDataStore

        @Provides @Singleton @ComicFormattingPreferencesDataStore
        fun provideComicFormattingPreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> =
            c.comicFormattingPreferencesDataStore

        @Provides
        @Singleton
        fun provideAppUpdatePreferencesStore(
            @AppUpdatePreferencesDataStore dataStore: DataStore<Preferences>,
        ): AppUpdatePreferencesStore = createAppUpdatePreferencesStore(dataStore)

        @Provides
        @Singleton
        fun provideContentCacheSettingsStore(
            @ContentCacheSettingsDataStore dataStore: DataStore<Preferences>,
        ): ContentCacheSettingsStore = createContentCacheSettingsStore(dataStore)
    }
}
