package com.riffle.shared.sync

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The sweep's target on iOS follows the saved WebDAV config (#1101). */
class IosAnnotationSyncTargetProviderTest {

    private class FakeConfigStore : AnnotationSyncConfigStore {
        val state = MutableStateFlow<AnnotationSyncConfig?>(null)
        override fun observe(): StateFlow<AnnotationSyncConfig?> = state
        override suspend fun save(config: AnnotationSyncConfig) {
            state.value = config
        }
        override suspend fun clear() {
            state.value = null
        }
    }

    private val factory = WebDavAnnotationSyncTargetFactory(
        HttpClient(MockEngine { error("no HTTP expected while resolving the target") }),
        IosDispatcherProvider,
    )

    @Test
    fun noConfigMeansNoTarget() {
        val provider = IosAnnotationSyncTargetProvider(FakeConfigStore(), factory)
        assertNull(provider.current())
    }

    @Test
    fun aSavedConfigYieldsAWebDavTargetReusedUntilTheConfigChanges() {
        val store = FakeConfigStore()
        val provider = IosAnnotationSyncTargetProvider(store, factory)
        store.state.value = AnnotationSyncConfig("https://dav.example.test/riffle", "u", "p")

        val first = provider.current()
        assertNotNull(first)
        assertSame(first, provider.current(), "same config → same target instance")

        store.state.value = AnnotationSyncConfig("https://dav.example.test/other", "u", "p")
        val second = provider.current()
        assertNotNull(second)
        assertNotSame(first, second, "changed config → rebuilt target")

        store.state.value = null
        assertNull(provider.current(), "cleared config → sync off")
    }

    @Test
    fun aBlankBaseUrlYieldsNoTarget() {
        val store = FakeConfigStore()
        store.state.value = AnnotationSyncConfig("   ", "u", "p")
        assertNull(IosAnnotationSyncTargetProvider(store, factory).current())
    }
}
