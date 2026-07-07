package com.riffle.core.data.di.modules

import android.content.Context
import com.riffle.core.data.AudioIdentityResolverImpl
import com.riffle.core.data.AudiobookBundleDownloader
import com.riffle.core.data.AudiobookChapterCacheRepositoryImpl
import com.riffle.core.data.AudiobookDownloadRepositoryImpl
import com.riffle.core.data.AudiobookRepositoryImpl
import com.riffle.core.data.OfflineAvailabilitySnapshot
import com.riffle.core.data.ReadaloudAudioRepositoryImpl
import com.riffle.core.data.ReadaloudLinkRepositoryImpl
import com.riffle.core.data.ReadaloudReviewRepositoryImpl
import com.riffle.core.data.StorytellerBundleAudiobookSource
import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.data.di.EpubDownloadsStore
import com.riffle.core.data.readaloudLinksByAbsItemKey
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AudiobookBundleApiImpl
import com.riffle.core.network.StorytellerBundleApiImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StreamingAudioModule {

    @Binds
    @Singleton
    abstract fun bindAudiobookRepository(impl: AudiobookRepositoryImpl): AudiobookRepository

    @Binds
    @Singleton
    abstract fun bindAudiobookDownloadRepository(impl: AudiobookDownloadRepositoryImpl): AudiobookDownloadRepository

    @Binds
    @Singleton
    abstract fun bindReadaloudLinkRepository(impl: ReadaloudLinkRepositoryImpl): ReadaloudLinkRepository

    @Binds
    @Singleton
    abstract fun bindReadaloudReviewRepository(impl: ReadaloudReviewRepositoryImpl): ReadaloudReviewRepository

    @Binds
    @Singleton
    abstract fun bindReadaloudReviewMutator(impl: ReadaloudReviewRepositoryImpl): com.riffle.core.domain.ReadaloudReviewMutator

    @Binds
    @Singleton
    abstract fun bindAudioIdentityResolver(impl: AudioIdentityResolverImpl): AudioIdentityResolver

    @Binds
    @Singleton
    abstract fun bindAudiobookChapterCacheRepository(impl: AudiobookChapterCacheRepositoryImpl): AudiobookChapterCacheRepository

    companion object {
        @Provides
        @Singleton
        fun provideStorytellerSidecarFetcher(
            api: StorytellerBundleApiImpl,
            @ApplicationContext context: Context,
            dispatchers: com.riffle.core.domain.DispatcherProvider,
        ): com.riffle.core.data.StorytellerSidecarFetcher =
            // Fast path: bounded streaming GET (sidecarStreamClient, 240 s callTimeout) stops at the
            // first audio entry. Full-download fallback: unbounded downloadBundle client, used only when
            // the fast path finds no SMIL before audio (non-standard bundle ordering — ADR 0028).
            com.riffle.core.data.StorytellerSidecarFetcher(
                bundleApi = { url, bookId, token, insecure -> api.streamSidecar(url, bookId, token, insecure) },
                fullBundleApi = { url, bookId, token, insecure -> api.downloadBundle(url, bookId, token, insecure) },
                tempDir = { context.cacheDir },
                dispatchers = dispatchers,
            )

        @Provides
        @Singleton
        fun provideReadaloudSidecarPrefetcher(store: com.riffle.core.data.ReadaloudSidecarStore): com.riffle.core.data.ReadaloudSidecarPrefetcher =
            store

        @Provides
        @Singleton
        fun provideReadaloudSidecarCache(store: com.riffle.core.data.ReadaloudSidecarStore): com.riffle.core.domain.ReadaloudSidecarCache =
            store

        @Provides
        @Singleton
        fun provideAudiobookBundleDownloader(
            @ApplicationContext context: Context,
            api: AudiobookBundleApiImpl,
            dispatchers: com.riffle.core.domain.DispatcherProvider,
        ): AudiobookBundleDownloader = AudiobookBundleDownloader(
            api = api,
            // Write into the same Downloads EPUB store the reader reads from — the synced bundle is
            // both the EPUB and the audio source (ADR 0023), so they share one file. Must mirror
            // LocalStoreImpl's dir/<sourceId>/<id> layout so downloadsStore.get(sourceId, id) finds it.
            targetFileProvider = { sourceId, id ->
                context.filesDir.resolve("downloads/epubs").resolve(sourceId).also { it.mkdirs() }.resolve("$id.epub")
            },
            dispatchers = dispatchers,
        )

        @Provides
        @Singleton
        fun provideReadaloudAudioRepository(
            downloader: AudiobookBundleDownloader,
            bundleProbe: StorytellerBundleApiImpl,
            @EpubCacheStore cacheStore: LocalStore,
            @EpubDownloadsStore downloadsStore: LocalStore,
            sourceRepository: SourceRepository,
            tokenStorage: TokenStorage,
            dispatchers: com.riffle.core.domain.DispatcherProvider,
        ): ReadaloudAudioRepository = ReadaloudAudioRepositoryImpl(
            downloader = downloader,
            bundleProbe = bundleProbe,
            cacheStore = cacheStore,
            downloadsStore = downloadsStore,
            sourceRepository = sourceRepository,
            tokenStorage = tokenStorage,
            dispatchers = dispatchers,
        )

        @Provides
        @Singleton
        fun provideBundleAudiobookSource(
            readaloudLinkRepository: ReadaloudLinkRepository,
            readaloudAudioRepository: ReadaloudAudioRepository,
            applicationScope: ApplicationScope,
        ): com.riffle.core.domain.BundleAudiobookSource =
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
