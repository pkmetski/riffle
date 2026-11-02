package com.riffle.core.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * The single global configuration for cloud annotation sync.
 *
 * Per-Server scoping is preserved by the WebDAV path layout
 * (`<baseUrl>/<sourceId>/<itemId>/annotations-<deviceId>.jsonld`),
 * not by duplicating this config per ABS Server.
 *
 * [password] is the user-supplied secret; storage implementations must keep it
 * in Android-Keystore-backed encrypted storage.
 */
data class AnnotationSyncConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
)

/**
 * Persistent store for [AnnotationSyncConfig]. Reactive: [observe] emits whenever
 * the config changes so the DI graph and Settings UI can react to (un)configuration.
 */
interface AnnotationSyncConfigStore {
    fun observe(): StateFlow<AnnotationSyncConfig?>
    suspend fun save(config: AnnotationSyncConfig)
    suspend fun clear()

    /**
     * Suspends until the store has finished loading its initial state from the backing storage.
     * The default implementation is a no-op for stores that read synchronously in their
     * constructor (Android EncryptedSharedPreferences); platforms that defer Keychain reads to
     * a background coroutine (iOS) override this to await that coroutine's completion.
     *
     * Callers that need the stored config to be present before acting — specifically the WebDAV
     * branch of [com.riffle.feature.source.ui.AddSourceViewModel.initFromRoute] — must call
     * this before reading [observe].
     */
    suspend fun awaitLoad(): Unit = Unit
}
