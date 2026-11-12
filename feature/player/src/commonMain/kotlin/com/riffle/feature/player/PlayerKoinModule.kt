package com.riffle.feature.player

import org.koin.dsl.module

val playerKoinModule = module {
    single { AudiobookHandoffState() }
    single { FollowLoopOrchestrator(clock = get(), progressFlushScope = get()) }
    single { AudiobookResumeResolver(positionStore = get(), clock = get()) }
    single {
        AudiobookReconciliationCoordinator(
            readerSyncFactory = get(),
            openReconcileTargets = get(),
            audioSyncStore = get(),
            readingSyncStore = get(),
            readaloudResumeStore = get(),
        )
    }
    single { NowPlayingStore() }
    single { AudiobookPlayIntentRegistry() }
}
