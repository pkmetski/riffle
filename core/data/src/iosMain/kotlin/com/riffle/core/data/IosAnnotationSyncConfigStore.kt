package com.riffle.core.data

import com.riffle.core.common.EncryptedKeyValueStore
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * iOS implementation of [AnnotationSyncConfigStore] that defers Keychain reads to a background
 * coroutine so the singleton creation never blocks its calling thread.
 *
 * The shared [AnnotationSyncConfigStoreImpl] reads [EncryptedKeyValueStore.get] synchronously
 * in its constructor. On Android this resolves to EncryptedSharedPreferences (fast), but on iOS
 * it resolves to [IosEncryptedKeyValueStore] which calls [SecItemCopyMatching] — a syscall that
 * blocks for 1–5 s per item on the first access after app launch (data-protection key derivation).
 *
 * With the shared implementation, every Koin call site that resolves [AnnotationSyncConfigStore]
 * for the first time incurs that penalty on the calling thread. The [AddSourceViewModel] factory
 * requests the store as a constructor parameter, so creating the ViewModel (which the credentials
 * form's [LaunchedEffect] does on [kotlinx.coroutines.Dispatchers.Default]) could block for 3–15 s
 * while the three annotation-sync Keychain items are read — presenting the user with a blank
 * screen and unresponsive text fields.
 *
 * This class initialises [state] as `null` and launches a [loadJob] on [scope] (the app-level
 * [kotlinx.coroutines.CoroutineScope] which runs on [kotlinx.coroutines.Dispatchers.Default]).
 * The [AnnotationSyncConfigStoreImpl] singleton creation therefore returns instantly; the
 * Keychain reads finish in the background and update [state] when done.
 *
 * [awaitLoad] bridges the gap for the one call site that must see the stored config before
 * acting: the WebDAV branch of [AddSourceViewModel.initFromRoute]. All other consumers — the
 * annotation-sweep infrastructure, [webdavBanner], [AnnotationSyncTargetHolder] — are flow-based
 * and react to the [state] update automatically.
 */
class IosAnnotationSyncConfigStore(
    private val store: EncryptedKeyValueStore,
    scope: CoroutineScope,
) : AnnotationSyncConfigStore {

    private val state = MutableStateFlow<AnnotationSyncConfig?>(null)
    private val mutex = Mutex()
    private val loadJob: Job = scope.launch { state.value = readFromStore() }

    override fun observe(): StateFlow<AnnotationSyncConfig?> = state.asStateFlow()

    override suspend fun awaitLoad() = loadJob.join()

    override suspend fun save(config: AnnotationSyncConfig) {
        loadJob.join()
        mutex.withLock {
            store.put(KEY_BASE_URL, config.baseUrl)
            store.put(KEY_USERNAME, config.username)
            store.put(KEY_PASSWORD, config.password)
            state.value = config
        }
    }

    override suspend fun clear() {
        loadJob.join()
        mutex.withLock {
            store.remove(KEY_BASE_URL)
            store.remove(KEY_USERNAME)
            store.remove(KEY_PASSWORD)
            state.value = null
        }
    }

    private fun readFromStore(): AnnotationSyncConfig? {
        val url = store.get(KEY_BASE_URL) ?: return null
        val user = store.get(KEY_USERNAME) ?: return null
        val pass = store.get(KEY_PASSWORD) ?: return null
        return AnnotationSyncConfig(baseUrl = url, username = user, password = pass)
    }

    companion object {
        private const val KEY_BASE_URL = "annotation_sync.base_url"
        private const val KEY_USERNAME = "annotation_sync.username"
        private const val KEY_PASSWORD = "annotation_sync.password"
    }
}
