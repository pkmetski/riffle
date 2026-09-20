package com.riffle.core.catalog.chitanka

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Kotlin/Native keeps Dispatchers.IO internal; Default is the nearest equivalent and is what
// IosDispatcherProvider already binds DispatcherProvider.io to.
internal actual val chitankaIoDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
