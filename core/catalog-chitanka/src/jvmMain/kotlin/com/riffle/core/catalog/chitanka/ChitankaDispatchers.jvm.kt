package com.riffle.core.catalog.chitanka

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val chitankaIoDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO
