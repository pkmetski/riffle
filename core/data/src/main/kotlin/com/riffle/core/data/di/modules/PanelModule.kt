package com.riffle.core.data.di.modules

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.riffle.core.data.PanelViewPreferencesStoreImpl
import com.riffle.core.data.comic.panel.AndroidPageImageDecoder
import com.riffle.core.data.comic.panel.JsonPanelStore
import com.riffle.core.data.di.PanelViewPreferencesDataStore
import com.riffle.core.data.di.panelViewPreferencesDataStore
import com.riffle.core.domain.comic.panel.PageImageDecoder
import com.riffle.core.domain.comic.panel.PanelDetector
import com.riffle.core.domain.comic.panel.PanelOrchestrator
import com.riffle.core.domain.comic.panel.PanelOrderer
import com.riffle.core.domain.comic.panel.PanelStore
import com.riffle.core.domain.comic.panel.PanelViewPreferencesStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * DI bindings for the CBZ Panel View pipeline (ADR 0043). The store lives under
 * `<filesDir>/comic-panels/`; the on-device decoder wraps `BitmapFactory`; the orchestrator
 * threads them together with the pure-JVM detector and orderer.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PanelModule {

    @Binds
    @Singleton
    abstract fun bindPageImageDecoder(impl: AndroidPageImageDecoder): PageImageDecoder

    @Binds
    @Singleton
    abstract fun bindPanelViewPreferencesStore(
        impl: PanelViewPreferencesStoreImpl,
    ): PanelViewPreferencesStore

    companion object {

        @Provides
        @Singleton
        fun providePanelStore(@ApplicationContext context: Context): PanelStore =
            JsonPanelStore(File(context.filesDir, "comic-panels").also { it.mkdirs() })

        @Provides
        @Singleton
        @PanelViewPreferencesDataStore
        fun providePanelViewPreferencesDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = context.panelViewPreferencesDataStore

        @Provides
        @Singleton
        fun providePanelDetector(): PanelDetector = PanelDetector()

        @Provides
        @Singleton
        fun providePanelOrderer(): PanelOrderer = PanelOrderer()

        @Provides
        @Singleton
        fun providePanelOrchestrator(
            store: PanelStore,
            decoder: PageImageDecoder,
            detector: PanelDetector,
            orderer: PanelOrderer,
        ): PanelOrchestrator = PanelOrchestrator(
            store = store,
            decoder = decoder,
            detector = detector,
            orderer = orderer,
        )
    }
}
