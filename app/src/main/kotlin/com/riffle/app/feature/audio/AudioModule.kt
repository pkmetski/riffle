package com.riffle.app.feature.audio

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the audio source / session seams (issue #333). The two ordered lists are
 * vended explicitly here because dispatch order matters — `StreamingReadaloudItemRestorer` must
 * try first (its mediaIds collide with what `AudiobookHttpItemRestorer` would otherwise claim),
 * and `BundleZipItemRestorer` is the trailing catch-all.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideMediaSourceFactories(
        http: HttpAudioSourceFactory,
        file: FileAudioSourceFactory,
        bundle: BundleAudioSourceFactory,
    ): List<MediaSourceFactory> = listOf(http, file, bundle)

    @Provides
    @Singleton
    fun provideMediaItemRestorers(
        streaming: StreamingReadaloudItemRestorer,
        audiobook: AudiobookHttpItemRestorer,
        bundle: BundleZipItemRestorer,
    ): List<MediaItemRestorer> = listOf(streaming, audiobook, bundle)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaSessionConnectorModule {
    @Binds
    abstract fun bindConnector(impl: DefaultMediaSessionConnector): MediaSessionConnector
}
