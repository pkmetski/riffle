package com.riffle.app.feature.reader

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReaderStateHolder @Inject constructor() {
    @Volatile var isReaderActive: Boolean = false
    @Volatile var isPanelOpen: Boolean = false

    // True while in-app audio (Readaloud today; audiobook playback later) is actively
    // playing. When set, the reader yields the volume keys to system volume control.
    @Volatile var isAudioPlaying: Boolean = false
}
