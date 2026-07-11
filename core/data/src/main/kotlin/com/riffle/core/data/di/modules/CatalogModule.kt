package com.riffle.core.data.di.modules

import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.DefaultCatalogRegistry
import com.riffle.core.domain.SourceType
import com.riffle.core.catalog.abs.AbsCatalogFactory
import com.riffle.core.catalog.chitanka.ChitankaCatalogFactory
import com.riffle.core.data.localfiles.LocalFilesCatalogFactory
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.Clock
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.data.di.qualifiers.WebSourceOkHttpClient
import com.riffle.core.network.AbsSessionApi
import okhttp3.OkHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton

/**
 * Wires the `Map<SourceType, CatalogFactory>` used by [CatalogRegistry]. Every SourceType
 * implementation registers its factory here via `@IntoMap` + `@SourceTypeKey`; repositories
 * consume [CatalogRegistry] rather than the map directly.
 *
 * LocalFiles factory (#438) reads catalog data from the local Room DAOs populated by
 * [LocalFilesScanner]; ABS factory pulls per-Source auth from [TokenStorage] and speaks HTTP.
 */
@Module
@InstallIn(SingletonComponent::class)
object CatalogModule {

    @Provides
    @Singleton
    @IntoMap
    @SourceTypeKey(SourceType.LOCAL_FILES)
    fun provideLocalFilesCatalogFactory(
        folderDao: LocalFilesFolderDao,
        fileDao: LocalFilesFileDao,
        fileFolderDao: com.riffle.core.database.LocalFilesFileFolderDao,
        itemDao: LibraryItemDao,
    ): CatalogFactory = LocalFilesCatalogFactory(
        folderDao = folderDao,
        fileDao = fileDao,
        fileFolderDao = fileFolderDao,
        itemDao = itemDao,
    )

    @Provides
    @Singleton
    @IntoMap
    @SourceTypeKey(SourceType.ABS)
    fun provideAbsCatalogFactory(
        libraryApi: AbsLibraryApi,
        playbackApi: AbsPlaybackApi,
        sessionApi: AbsSessionApi,
        bookmarkApi: AbsBookmarkApi,
        serverInfoApi: AbsServerInfoApi,
        tokenStorage: TokenStorage,
        deviceIdStore: DeviceIdStore,
        clock: Clock,
    ): CatalogFactory = AbsCatalogFactory(
        libraryApi = libraryApi,
        playbackApi = playbackApi,
        sessionApi = sessionApi,
        bookmarkApi = bookmarkApi,
        serverInfoApi = serverInfoApi,
        tokenStorage = tokenStorage,
        deviceIdStore = deviceIdStore,
        clock = clock,
    )

    @Provides
    @Singleton
    @IntoMap
    @SourceTypeKey(SourceType.CHITANKA)
    fun provideChitankaCatalogFactory(
        @WebSourceOkHttpClient okHttpClient: OkHttpClient,
    ): CatalogFactory = ChitankaCatalogFactory(
        okHttpClient = okHttpClient,
        userAgent = "Riffle/dev (Android) chitanka-source",
    )

    @Provides
    @Singleton
    fun provideCatalogRegistry(
        factories: Map<SourceType, @JvmSuppressWildcards CatalogFactory>,
        sourceRepository: SourceRepository,
    ): CatalogRegistry = DefaultCatalogRegistry(factories, sourceRepository)
}
