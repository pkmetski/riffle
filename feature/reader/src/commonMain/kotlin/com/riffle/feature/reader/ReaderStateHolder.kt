package com.riffle.feature.reader

import kotlin.concurrent.Volatile

class ReaderStateHolder constructor() {
    @Volatile var isReaderActive: Boolean = false
    @Volatile var isPanelOpen: Boolean = false

    // True while in-app audio (Readaloud today; audiobook playback later) is actively
    // playing. When set, the reader yields the volume keys to system volume control.
    @Volatile var isAudioPlaying: Boolean = false
}
