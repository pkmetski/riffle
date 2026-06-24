package com.riffle.core.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * The single global configuration for cloud annotation sync.
 *
 * Per-Server scoping is preserved by the WebDAV path layout
 * (`<baseUrl>/<serverId>/<itemId>/annotations-<deviceId>.jsonld`),
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
}
