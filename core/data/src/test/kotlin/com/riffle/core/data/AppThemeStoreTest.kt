package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.domain.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppThemeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = AppThemeStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("app_theme_prefs.preferences_pb") },
        )
    )

    @Test
    fun `default appTheme is System when DataStore is empty`() = testScope.runTest {
        assertEquals(AppTheme.System, buildStore().appTheme.first())
    }

    @Test
    fun `setAppTheme Dark returns Dark on read`() = testScope.runTest {
        val store = buildStore()
        store.setAppTheme(AppTheme.Dark)
        assertEquals(AppTheme.Dark, store.appTheme.first())
    }

    @Test
    fun `setAppTheme Light after Dark returns Light`() = testScope.runTest {
        val store = buildStore()
        store.setAppTheme(AppTheme.Dark)
        store.setAppTheme(AppTheme.Light)
        assertEquals(AppTheme.Light, store.appTheme.first())
    }

    @Test
    fun `appTheme persists across store instances`() {
        val file = tmp.newFile("app_theme_round_trip.preferences_pb")

        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        runBlocking {
            val store = AppThemeStoreImpl(
                PreferenceDataStoreFactory.create(scope = writeScope, produceFile = { file })
            )
            store.setAppTheme(AppTheme.Dark)
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val store2 = AppThemeStoreImpl(
            PreferenceDataStoreFactory.create(scope = readScope, produceFile = { file })
        )
        assertEquals(AppTheme.Dark, runBlocking { store2.appTheme.first() })
        readScope.cancel()
    }
}
