package com.riffle.shared

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade

/**
 * Coil's network fetcher is discovered via a JVM ServiceLoader on Android, but on Kotlin/Native it
 * has to be registered by hand. Without this, every remote image request (the source favicons in
 * `SourceIcon` / `SourceTypeIcon`, cover art) fails immediately and the UI only ever shows the
 * bundled monogram fallback.
 */
internal fun iosImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory()) }
        .crossfade(true)
        .build()
