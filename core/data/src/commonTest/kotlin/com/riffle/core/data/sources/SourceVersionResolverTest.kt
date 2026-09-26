package com.riffle.core.data.sources

import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.KomgaServerInfoApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shared version lookup behind `SourceRepository.getSourceVersion` on both platforms (#1101).
 * Runs on the JVM and the iOS simulator; before the resolver existed iOS returned null
 * unconditionally, which these assertions would catch.
 */
class SourceVersionResolverTest {

    private class FakeTokens(private val tokens: Map<String, String>, private val passwords: Map<String, String>) : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = tokens[sourceId]
        override suspend fun deleteToken(sourceId: String) {}
        override suspend fun getPassword(sourceId: String): String? = passwords[sourceId]
    }

    private class FakeAbs(private val version: String?) : AbsServerInfoApi {
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        override suspend fun getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String? {
            calls += Triple(baseUrl, token, insecureAllowed)
            return version
        }
        override suspend fun getCurrentUserId(baseUrl: String, token: String, insecureAllowed: Boolean): String? = null
    }

    private class FakeKomga(private val version: String?) : KomgaServerInfoApi {
        val calls = mutableListOf<List<Any>>()
        override suspend fun getServerVersion(baseUrl: String, username: String, password: String, insecureAllowed: Boolean): String? {
            calls += listOf(baseUrl, username, password, insecureAllowed)
            return version
        }
    }

    private fun source(id: String, type: SourceType = SourceType.ABS, serverType: ServerType = ServerType.AUDIOBOOKSHELF) = Source(
        id = id,
        url = SourceUrl.parse("https://$id.example.test/")!!,
        isActive = true,
        insecureConnectionAllowed = true,
        username = "alice",
        type = type,
        serverType = serverType,
    )

    @Test
    fun absSourceResolvesThroughTheServerInfoApiWithItsToken() = runTest {
        val abs = FakeAbs("2.19.0")
        val resolver = SourceVersionResolver(FakeTokens(mapOf("abs-1" to "tok"), emptyMap()), abs, FakeKomga("x"))

        assertEquals("2.19.0", resolver.resolve(source("abs-1")))
        assertEquals(listOf(Triple("https://abs-1.example.test", "tok", true)), abs.calls)
    }

    @Test
    fun komgaSourceResolvesThroughBasicAuthWithTheStoredPassword() = runTest {
        val komga = FakeKomga("1.14.1")
        val abs = FakeAbs("should-not-be-used")
        val resolver = SourceVersionResolver(FakeTokens(mapOf("k-1" to "tok"), mapOf("k-1" to "pw")), abs, komga)

        assertEquals("1.14.1", resolver.resolve(source("k-1", type = SourceType.KOMGA)))
        assertEquals(listOf(listOf<Any>("https://k-1.example.test", "alice", "pw", true)), komga.calls)
        assertEquals(emptyList<Triple<String, String, Boolean>>(), abs.calls)
    }

    @Test
    fun storytellerServiceHasNoVersion() = runTest {
        val abs = FakeAbs("2.19.0")
        val resolver = SourceVersionResolver(FakeTokens(mapOf("st-1" to "tok"), emptyMap()), abs, FakeKomga("x"))

        assertNull(resolver.resolve(source("st-1", serverType = ServerType.STORYTELLER_SERVICE)))
        assertEquals(emptyList<Triple<String, String, Boolean>>(), abs.calls)
    }

    @Test
    fun missingCredentialsShortCircuitToNullWithoutANetworkCall() = runTest {
        val abs = FakeAbs("2.19.0")
        val komga = FakeKomga("1.14.1")
        val resolver = SourceVersionResolver(FakeTokens(emptyMap(), emptyMap()), abs, komga)

        assertNull(resolver.resolve(source("abs-1")))
        assertNull(resolver.resolve(source("k-1", type = SourceType.KOMGA)))
        assertEquals(emptyList<Triple<String, String, Boolean>>(), abs.calls)
        assertEquals(emptyList<List<Any>>(), komga.calls)
    }
}
