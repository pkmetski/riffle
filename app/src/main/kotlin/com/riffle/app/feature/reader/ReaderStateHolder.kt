package com.riffle.app.feature.reader

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReaderStateHolder @Inject constructor() {
    @Volatile var isReaderActive: Boolean = false
    @Volatile var isPanelOpen: Boolean = false
}
