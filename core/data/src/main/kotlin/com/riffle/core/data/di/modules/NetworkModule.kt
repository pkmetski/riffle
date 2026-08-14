package com.riffle.core.data.di.modules

import android.content.Context
import com.riffle.core.data.di.qualifiers.StreamingHttpClient
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsApiClient
import com.riffle.core.data.di.qualifiers.WebSourceOkHttpClient
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
import com.riffle.core.sources.abs.AbsSourceAdapter
import com.riffle.core.sources.komga.KomgaSourceAdapter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {

    @Binds
    @Singleton
    abstract fun bindAbsApi(impl: AbsApiClient): AbsApi

    @Binds
    @Singleton
    abstract fun bindAbsLibraryApi(impl: AbsApiClient): AbsLibraryApi

    @Binds
    @Singleton
    abstract fun bindAbsSessionApi(impl: AbsApiClient): AbsSessionApi

    @Binds
    @Singleton
    abstract fun bindAbsServerInfoApi(impl: AbsApiClient): AbsServerInfoApi

    @Binds
    @Singleton
    abstract fun bindAbsPlaybackApi(impl: AbsApiClient): AbsPlaybackApi

    @Binds
    @Singleton
    abstract fun bindAbsBookmarkApi(impl: AbsApiClient): AbsBookmarkApi

    @Binds
    @Singleton
    abstract fun bindStorytellerApi(impl: StorytellerApiClient): StorytellerApi

    @Binds
    @Singleton
    abstract fun bindStorytellerLibraryApi(impl: StorytellerApiClient): StorytellerLibraryApi

    companion object {
        /**
         * Shared default client for ABS, Storyteller, and the GitHub releases updater. Carries a
         * 20 MB disk cache and a network interceptor that stamps `Cache-Control: public, max-age=N`
         * on a small allowlist of read-only endpoints ([DEFAULT_HTTP_CACHE_RULES]). Writes, progress,
         * sessions, and binaries do not match any rule and pass through as before.
         */
        @Provides
        @Singleton
        fun provideJvmHttpClientPool(
            @ApplicationContext context: Context,
        ): JvmHttpClientPool {
            val cacheDir = File(context.cacheDir, "default-http")
            return createDefaultJvmHttpClientPool(cacheDir, DEFAULT_HTTP_CACHE_BYTES)
        }

        private const val DEFAULT_HTTP_CACHE_BYTES: Long = 20L * 1024L * 1024L

        /**
         * OkHttp client for web-source scrapers (ADR 0052). Carries a 10 MB disk cache,
         * a network interceptor that forces `max-age=86400` (24 h) onto all successful
         * responses since chitanka/gramofonche send no cache headers, and an application
         * interceptor that falls back to any cached copy on network failure. Cache dir
         * lives under `context.cacheDir/web-source-http` so Android Settings → Clear Cache
         * discards it cleanly.
         */
        @Provides
        @Singleton
        @WebSourceOkHttpClient
        fun provideWebSourceHttpClient(
            @ApplicationContext context: Context,
        ): HttpClient {
            val cacheDir = File(context.cacheDir, "web-source-http")
            return createWebSourceHttpClient(
                cacheDirectory = cacheDir,
                cacheSizeBytes = WEB_SOURCE_CACHE_BYTES,
                maxAgeSeconds = WEB_SOURCE_MAX_AGE_SECONDS,
            )
        }

        private const val WEB_SOURCE_CACHE_BYTES: Long = 10L * 1024L * 1024L
        private const val WEB_SOURCE_MAX_AGE_SECONDS: Int = 24 * 60 * 60

        @Provides
        @Singleton
        fun provideGitHubReleaseApi(httpClient: HttpClient): GitHubReleaseApi =
            GitHubReleaseApi(httpClient)

        @Provides
        @Singleton
        fun provideAbsApiClient(httpClient: HttpClient): AbsApiClient =
            AbsApiClient(httpClient)

        @Provides
        @Singleton
        fun provideAbsFileDownloadApi(httpClient: HttpClient): AbsFileDownloadApi =
            AbsFileDownloadApiClient(httpClient)

        @Provides
        @Singleton
        fun provideStorytellerApiClient(httpClient: HttpClient): StorytellerApiClient =
            StorytellerApiClient(httpClient)

        @Provides
        @Singleton
        fun provideStorytellerBundleApiImpl(httpClient: HttpClient): StorytellerBundleApiImpl =
            StorytellerBundleApiImpl(httpClient)

        @Provides
        @Singleton
        fun provideAudiobookBundleApi(
            @StreamingHttpClient httpClient: HttpClient,
        ): AudiobookBundleApiImpl = AudiobookBundleApiImpl(httpClient)

        @Provides
        @Singleton
        fun provideStorytellerPositionApi(httpClient: HttpClient): StorytellerPositionApi =
            StorytellerPositionApiImpl(httpClient)

        @Provides
        @Singleton
        fun provideKtorHttpClient(pool: JvmHttpClientPool): HttpClient =
            pool.defaultHttpClient()

        @Provides
        @Singleton
        @StreamingHttpClient
        fun provideStreamingHttpClient(pool: JvmHttpClientPool): HttpClient =
            pool.streamingHttpClient()

        @Provides
        @Singleton
        fun provideAbsSourceAdapter(
            absApi: AbsApi,
            libraryApi: AbsLibraryApi,
            storytellerApi: StorytellerApi,
        ): AbsSourceAdapter = AbsSourceAdapter(absApi, libraryApi, storytellerApi)

        @Provides
        @Singleton
        fun provideKomgaServerInfoApiClient(httpClient: HttpClient): KomgaServerInfoApi =
            KomgaServerInfoApiClient(httpClient)

        @Provides
        @Singleton
        fun provideKomgaSourceAdapter(httpClient: HttpClient): KomgaSourceAdapter =
            KomgaSourceAdapter(httpClient)
    }
}
