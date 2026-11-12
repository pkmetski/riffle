package com.riffle.feature.reader

import org.koin.dsl.module

val readerKoinModule = module {
    single { ReaderStateHolder() }
    single { VolumeNavigationController() }
    single { ProgressFlushScope(applicationScope = get()) }
    factory<AnnotationSession.Factory> {
        AnnotationSession.Factory { scope, startLiveSync, scheduleSync, syncOnOpen, syncOnClose, mergeAfterEdit, commitDraftWithNote ->
            AnnotationSession(
                scope = scope,
                annotationStore = get(),
                annotationStatusStore = get(),
                highlightColorPreferencesStore = get(),
                emphasisPreferencesStore = get(),
                progressFlushScope = get(),
                startLiveSync = startLiveSync,
                scheduleSync = scheduleSync,
                syncOnOpen = syncOnOpen,
                syncOnClose = syncOnClose,
                mergeAfterEdit = mergeAfterEdit,
                commitDraftWithNote = commitDraftWithNote,
            )
        }
    }
}
