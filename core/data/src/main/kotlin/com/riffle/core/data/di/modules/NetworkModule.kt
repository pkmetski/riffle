package com.riffle.core.data.di.modules

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.AudiobookBundleApiImpl
import com.riffle.core.network.GitHubReleaseApi
import com.riffle.core.network.StorytellerApi
import com.riffle.core.network.StorytellerApiClient
import com.riffle.core.network.StorytellerBundleApiImpl
import com.riffle.core.network.StorytellerLibraryApi
import com.riffle.core.network.StorytellerPositionApi
import com.riffle.core.network.StorytellerPositionApiImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
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
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

        @Provides
        @Singleton
        fun provideGitHubReleaseApi(okHttpClient: OkHttpClient, dispatchers: DispatcherProvider): GitHubReleaseApi =
            GitHubReleaseApi(okHttpClient, dispatchers)

        @Provides
        @Singleton
        fun provideAbsApiClient(okHttpClient: OkHttpClient, dispatchers: DispatcherProvider): AbsApiClient =
            AbsApiClient(okHttpClient, dispatchers)

        @Provides
        @Singleton
        fun provideStorytellerApiClient(okHttpClient: OkHttpClient, dispatchers: DispatcherProvider): StorytellerApiClient =
            StorytellerApiClient(okHttpClient, dispatchers)

        @Provides
        @Singleton
        fun provideStorytellerBundleApiImpl(okHttpClient: OkHttpClient, dispatchers: DispatcherProvider): StorytellerBundleApiImpl =
            StorytellerBundleApiImpl(okHttpClient, dispatchers)

        @Provides
        @Singleton
        fun provideAudiobookBundleApi(okHttpClient: OkHttpClient, dispatchers: DispatcherProvider): AudiobookBundleApiImpl =
            AudiobookBundleApiImpl(okHttpClient, dispatchers)

        @Provides
        @Singleton
        fun provideStorytellerPositionApi(okHttpClient: OkHttpClient, dispatchers: DispatcherProvider): StorytellerPositionApi =
            StorytellerPositionApiImpl(okHttpClient, dispatchers)
    }
}
