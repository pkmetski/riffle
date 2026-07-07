package com.riffle.app.feature.reader.di

import com.riffle.app.feature.reader.FiguresInRangeResolver
import com.riffle.app.feature.reader.NoopFiguresInRangeResolver
import com.riffle.app.feature.reader.highlights.NoopResourceFetcher
import com.riffle.app.feature.reader.highlights.ResourceFetcher
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

    // Bound to the always-null stub (Task 9 follow-up — see NoopResourceFetcher's class doc):
    // wiring a real Container-backed fetcher needs the source Publication in scope at Highlights
    // mode's load point, which EpubReaderViewModel.openBook's Highlights branch does not hold today
    // (it diverts before the normal container-loading path). Figures currently render
    // figcaption-only until that wiring lands.
    @Binds
    abstract fun bindResourceFetcher(impl: NoopResourceFetcher): ResourceFetcher
}
