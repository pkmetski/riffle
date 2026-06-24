package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

class AnnotationSyncConfigStoreImpl @Inject constructor(
    private val store: EncryptedKeyValueStore,
) : AnnotationSyncConfigStore {

    private val state = MutableStateFlow(readFromStore())
    private val mutex = Mutex()

    override fun observe(): StateFlow<AnnotationSyncConfig?> = state.asStateFlow()

    override suspend fun save(config: AnnotationSyncConfig) {
        mutex.withLock {
            store.put(KEY_BASE_URL, config.baseUrl)
            store.put(KEY_USERNAME, config.username)
            store.put(KEY_PASSWORD, config.password)
            state.value = config
        }
    }

    override suspend fun clear() {
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
