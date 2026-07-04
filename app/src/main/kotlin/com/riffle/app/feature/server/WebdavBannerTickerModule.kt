package com.riffle.app.feature.server

import com.riffle.app.feature.server.AddServerViewModel.Companion.WEBDAV_BANNER_TICKER
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Named
import javax.inject.Singleton

/**
 * Wall-clock ticker driving the [AddServerViewModel.webdavBanner] combine — emits Unit once a
 * minute so the "Last sync N ago" relative label advances even while nothing upstream changes.
 * Without it the banner freezes whenever offline: WorkManager gates the sweep on CONNECTED, so
 * no CycleOutcome reports fire, and the label sticks at whatever value was computed when
 * connectivity dropped.
 */
@Module
@InstallIn(SingletonComponent::class)
object WebdavBannerTickerModule {

    private const val TICK_INTERVAL_MS = 60_000L

    @Provides
    @Singleton
    @Named(WEBDAV_BANNER_TICKER)
    fun provideWebdavBannerTicker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_INTERVAL_MS)
        }
    }
}
