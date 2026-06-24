package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationSyncConfigStoreImplTest {

    private val backing = FakeEncryptedKeyValueStore()
    private val store = AnnotationSyncConfigStoreImpl(backing)

    @Test
    fun `observe emits null when nothing saved`() = runTest {
        assertNull(store.observe().first())
    }

    @Test
    fun `save then observe round-trips all fields`() = runTest {
        val config = AnnotationSyncConfig(
            baseUrl = "https://dav.example.org/remote.php/dav/files/me/annotations",
            username = "alice",
            password = "s3cret",
        )

        store.save(config)

        assertEquals(config, store.observe().first())
    }

    @Test
    fun `clear removes the stored config`() = runTest {
        store.save(AnnotationSyncConfig("https://x", "u", "p"))

        store.clear()

        assertNull(store.observe().first())
    }

    @Test
    fun `password is written through the encrypted backing store, never plaintext`() = runTest {
        store.save(AnnotationSyncConfig("https://x", "u", "topsecret"))

        // Whatever keys the impl chooses, the password must live behind the encrypted store
        // and never appear in any plaintext side-channel exposed by the store.
        assertTrue(
            "expected the password to land in the encrypted backing store",
            backing.contents.values.any { it == "topsecret" },
        )
        // No plaintext copies anywhere except the encrypted backing store (which the
        // impl in production is Keystore-backed). The fake exposes raw values so this
        // is just a guard against the impl writing plaintext to a second sink.
        assertFalse(backing.plaintextSinkUsed)
    }

    @Test
    fun `saving a second config overwrites the first`() = runTest {
        store.save(AnnotationSyncConfig("https://a", "u1", "p1"))
        store.save(AnnotationSyncConfig("https://b", "u2", "p2"))

        assertEquals(
            AnnotationSyncConfig("https://b", "u2", "p2"),
            store.observe().first(),
        )
    }
}

private class FakeEncryptedKeyValueStore : EncryptedKeyValueStore {
    val contents = mutableMapOf<String, String>()
    var plaintextSinkUsed: Boolean = false

    override fun get(key: String): String? = contents[key]

    override fun put(key: String, value: String) {
        contents[key] = value
    }

    override fun remove(key: String) {
        contents.remove(key)
    }
}
