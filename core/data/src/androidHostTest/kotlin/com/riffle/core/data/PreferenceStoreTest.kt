package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun newDataStore(name: String) = PreferenceDataStoreFactory.create(
        scope = testScope.backgroundScope,
        produceFile = { tmp.newFile("$name.preferences_pb") },
    )

    private enum class Pet { DOG, CAT, BIRD }

    @Test fun `boolean codec round-trips and defaults`() = testScope.runTest {
        val store = preferenceStore(newDataStore("b"), PrefCodecs.boolean("k", default = true))
        assertEquals(true, store.flow.first())
        store.update(false)
        assertEquals(false, store.flow.first())
    }

    @Test fun `int codec round-trips and defaults`() = testScope.runTest {
        val store = preferenceStore(newDataStore("i"), PrefCodecs.int("k", default = 7))
        assertEquals(7, store.flow.first())
        store.update(42)
        assertEquals(42, store.flow.first())
    }

    @Test fun `float codec round-trips and defaults`() = testScope.runTest {
        val store = preferenceStore(newDataStore("f"), PrefCodecs.float("k", default = 1.5f))
        assertEquals(1.5f, store.flow.first())
        store.update(2.25f)
        assertEquals(2.25f, store.flow.first())
    }

    @Test fun `double codec round-trips and defaults`() = testScope.runTest {
        val store = preferenceStore(newDataStore("d"), PrefCodecs.double("k", default = 1.5))
        assertEquals(1.5, store.flow.first(), 0.0)
        store.update(3.5)
        assertEquals(3.5, store.flow.first(), 0.0)
    }

    @Test fun `string codec round-trips and defaults`() = testScope.runTest {
        val store = preferenceStore(newDataStore("s"), PrefCodecs.string("k", default = "hi"))
        assertEquals("hi", store.flow.first())
        store.update("bye")
        assertEquals("bye", store.flow.first())
    }

    @Test fun `enum codec round-trips and defaults`() = testScope.runTest {
        val store = preferenceStore(
            newDataStore("e"),
            PrefCodecs.enum("k", Pet.DOG, Pet.entries.toTypedArray()),
        )
        assertEquals(Pet.DOG, store.flow.first())
        store.update(Pet.BIRD)
        assertEquals(Pet.BIRD, store.flow.first())
    }

    @Test fun `enum codec falls back to default on unknown stored value`() = testScope.runTest {
        val ds = newDataStore("efallback")
        ds.edit { it[stringPreferencesKey("k")] = "PARROT" }
        val store = preferenceStore(ds, PrefCodecs.enum("k", Pet.CAT, Pet.entries.toTypedArray()))
        assertEquals(Pet.CAT, store.flow.first())
    }

}
