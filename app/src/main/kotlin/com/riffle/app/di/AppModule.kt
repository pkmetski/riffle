@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.di

import android.content.Context
import com.riffle.app.feature.reader.SystemTimeProvider
import com.riffle.app.feature.reader.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Application-lifetime [CoroutineScope] for work that must outlive any single screen — e.g. downloads
 * started on the detail screen that should keep running after the user navigates away (the screen's
 * `viewModelScope` would otherwise cancel them on back).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadScope

/**
 * Application-lifetime [CoroutineScope] for terminal progress writes (the close / pause flush) that
 * must survive the screen's ViewModel being cleared — see `ProgressFlushScope`. Without it, a flush
 * launched on `viewModelScope` is cancelled mid-PATCH when the user leaves the screen right after
 * triggering it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProgressFlush

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @DownloadScope
    fun provideDownloadScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    @ProgressFlush
    fun provideProgressFlushScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideDefaultHttpClient(): DefaultHttpClient = DefaultHttpClient()

    @Provides
    @Singleton
    fun provideAssetRetriever(
        @ApplicationContext context: Context,
        httpClient: DefaultHttpClient
    ): AssetRetriever = AssetRetriever(context.contentResolver, httpClient)

    @Provides
    @Singleton
    fun provideTimeProvider(impl: SystemTimeProvider): TimeProvider = impl

    @Provides
    @Singleton
    fun providePublicationOpener(
        @ApplicationContext context: Context,
        httpClient: DefaultHttpClient,
        assetRetriever: AssetRetriever
    ): PublicationOpener = PublicationOpener(
        DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = PdfiumDocumentFactory(context))
    )
}
