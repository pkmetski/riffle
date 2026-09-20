package com.riffle.core.catalog.chitanka

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for the parallel HEAD/Range probes and the EPUB stream pull in [ChitankaCatalog].
 *
 * `Dispatchers.IO` is declared on Kotlin/Native in kotlinx-coroutines 1.11.0 but is `internal`
 * there, so referencing it from `commonMain` does not compile for the iOS targets. The JVM
 * actual is `Dispatchers.IO`, unchanged from when these sources lived in `jvmMain`; the iOS
 * actual is `Dispatchers.Default`, the same substitution `IosDispatcherProvider` already makes
 * for `DispatcherProvider.io`.
 */
internal expect val chitankaIoDispatcher: CoroutineDispatcher
