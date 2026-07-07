package com.riffle.app.feature.reader.di

import com.riffle.app.feature.reader.FiguresInRangeResolver
import com.riffle.app.feature.reader.NoopFiguresInRangeResolver
import com.riffle.app.feature.reader.readaloud.PlayerController
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
internal abstract class ReaderModule {
    @Binds
    abstract fun bindPlayerController(impl: PlayerCoordinator): PlayerController

    // Bound to the always-empty stub until a JS CFI→DOM resolver exists (see
    // FiguresInRangeResolver's class doc). Swapping this binding for WebViewFiguresInRangeResolver
    // is the only change needed once that resolver lands.
    @Binds
    abstract fun bindFiguresInRangeResolver(impl: NoopFiguresInRangeResolver): FiguresInRangeResolver
}
