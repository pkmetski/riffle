package com.riffle.core.data.developer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DeveloperOptionsRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun repo(patStore: PatStore = InMemoryPatStore()) = DeveloperOptionsRepositoryImpl(
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("dev_opts.preferences_pb") },
        ),
        patStore = patStore,
    )

    @Test
    fun `developerModeEnabled starts false`() = testScope.runTest {
        assertFalse(repo().developerModeEnabled.first())
    }

    @Test
    fun `setDeveloperModeEnabled true then read returns true`() = testScope.runTest {
        val r = repo()
        r.setDeveloperModeEnabled(true)
        assertTrue(r.developerModeEnabled.first())
    }

    @Test
    fun `setDeveloperModeEnabled false after true returns false`() = testScope.runTest {
        val r = repo()
        r.setDeveloperModeEnabled(true)
        r.setDeveloperModeEnabled(false)
        assertFalse(r.developerModeEnabled.first())
    }

    @Test
    fun `github pat starts null`() = testScope.runTest {
        assertNull(repo().getGithubPat())
    }

    @Test
    fun `setGithubPat then getGithubPat returns same value`() = testScope.runTest {
        val r = repo()
        r.setGithubPat("ghp_test1234")
        assertEquals("ghp_test1234", r.getGithubPat())
    }

    @Test
    fun `setGithubPat null clears the value`() = testScope.runTest {
        val r = repo()
        r.setGithubPat("ghp_test1234")
        r.setGithubPat(null)
        assertNull(r.getGithubPat())
    }
}

private class InMemoryPatStore : PatStore {
    private var value: String? = null
    override fun get(): String? = value
    override fun set(pat: String?) { value = pat }
}
