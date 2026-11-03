package com.riffle.core.data

import com.riffle.core.common.EncryptedKeyValueStore
import com.riffle.core.domain.AnnotationSyncConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class IosAnnotationSyncConfigStoreTest {

    private fun makeStore(backing: FakeIosEncryptedKeyValueStore = FakeIosEncryptedKeyValueStore()) =
        IosAnnotationSyncConfigStore(
            store = backing,
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
        )

    @Test
    fun observeEmitsNullWhenNothingSaved() = runTest {
        val s = makeStore()
        s.awaitLoad()
        assertNull(s.observe().first())
    }

    @Test
    fun awaitLoadThenObserveReturnsPersistedConfig() = runTest {
        val backing = FakeIosEncryptedKeyValueStore()
        val config = AnnotationSyncConfig(
            baseUrl = "https://dav.example.org/remote.php/dav/files/me/annotations",
            username = "alice",
            password = "s3cret",
        )
        backing.put("annotation_sync.base_url", config.baseUrl)
        backing.put("annotation_sync.username", config.username)
        backing.put("annotation_sync.password", config.password)

        val s = makeStore(backing)
        s.awaitLoad()

        assertEquals(config, s.observe().value)
    }

    @Test
    fun saveRoundTripsAllFields() = runTest {
        val s = makeStore()
        val config = AnnotationSyncConfig(
            baseUrl = "https://dav.example.org/remote.php/dav/files/me/",
            username = "bob",
            password = "hunter2",
        )
        s.save(config)
        assertEquals(config, s.observe().first())
    }

    @Test
    fun clearRemovesStoredConfig() = runTest {
        val s = makeStore()
        s.save(AnnotationSyncConfig("https://x", "u", "p"))
        s.clear()
        assertNull(s.observe().first())
    }

    @Test
    fun savingSecondConfigOverwritesFirst() = runTest {
        val s = makeStore()
        s.save(AnnotationSyncConfig("https://a", "u1", "p1"))
        s.save(AnnotationSyncConfig("https://b", "u2", "p2"))
        assertEquals(AnnotationSyncConfig("https://b", "u2", "p2"), s.observe().first())
    }

    @Test
    fun observeValueIsNullImmediatelyAfterConstruction() = runTest {
        val s = IosAnnotationSyncConfigStore(
            store = SlowFakeEncryptedKeyValueStore(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        assertNull(s.observe().value, "state must be null before background load completes")
        s.awaitLoad()
    }
}

private class FakeIosEncryptedKeyValueStore : EncryptedKeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

private class SlowFakeEncryptedKeyValueStore : EncryptedKeyValueStore {
    override fun get(key: String): String? = null
    override fun put(key: String, value: String) {}
    override fun remove(key: String) {}
}
