package com.riffle.core.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Production [DispatcherProvider] — delegates straight to `kotlinx.coroutines.Dispatchers`. Bound in `AppModule`. */
object DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Main.immediate
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}
