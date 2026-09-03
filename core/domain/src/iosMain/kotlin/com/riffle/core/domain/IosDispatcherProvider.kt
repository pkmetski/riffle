package com.riffle.core.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object IosDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Main.immediate
    // Kotlin/Native has no Dispatchers.IO; Default is the nearest equivalent.
    override val io: CoroutineDispatcher get() = Dispatchers.Default
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}
