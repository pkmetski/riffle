package com.riffle.feature.reader

import org.koin.dsl.module

val readerKoinModule = module {
    single { ReaderStateHolder() }
    single { VolumeNavigationController() }
    single { ProgressFlushScope(applicationScope = get()) }
}
