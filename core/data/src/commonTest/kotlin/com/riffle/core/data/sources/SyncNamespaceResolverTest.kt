package com.riffle.core.data.sources

import com.riffle.core.domain.AbsWebSourceDescriptor
import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The shared `ensureSyncNamespace` algorithm (#529) both platforms now run (#1101). iOS used to
 * inherit the interface default — `LocalOnly("Unknown source.")` for every source — which is
 * exactly what [pendingRemoteIdIsFetchedPersistedAndProjected] would report as a failure.
 */
class SyncNamespaceResolverTest {

    private class FakeTokens(private val tokens: Map<String, String>) : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = tokens[sourceId]
        override suspend fun deleteToken(sourceId: String) {}
    }

    private class FakeRemoteId(private val id: String?) : RemoteUserIdResolver {
        var calls = 0
        override suspend fun resolve(source: Source, token: String): String? {
            calls++
            return id
        }
    }

    private fun source(id: String, absUserId: String? = null, type: SourceType = SourceType.ABS, serverType: ServerType = ServerType.AUDIOBOOKSHELF) =
        Source(
            id = id,
            url = SourceUrl.parse("https://$id.example.test/")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "alice",
            type = type,
            serverType = serverType,
            absUserId = absUserId,
        )

    private fun resolver(
        sources: Map<String, Source>,
        tokens: Map<String, String>,
        remote: RemoteUserIdResolver? = null,
        persisted: MutableList<Pair<String, String>> = mutableListOf(),
    ) = SyncNamespaceResolver(
        sourceLookup = { sources[it] },
        tokenStorage = FakeTokens(tokens),
        remoteUserIdResolvers = remote?.let { mapOf(SourceType.ABS to it, SourceType.KOMGA to it) }.orEmpty(),
        persistRemoteId = { id, remoteId -> persisted += id to remoteId },
    )

    @Test
    fun unknownSourceIsLocalOnly() = runTest {
        val ns = resolver(emptyMap(), emptyMap()).resolve("nope")
        assertIs<SyncNamespace.LocalOnly>(ns)
    }

    @Test
    fun sourceWithAKnownRemoteIdIsConfiguredWithoutAnyNetworkCall() = runTest {
        val remote = FakeRemoteId("fetched")
        val ns = resolver(mapOf("s" to source("s", absUserId = "u-1")), mapOf("s" to "tok"), remote).resolve("s")

        assertEquals(SyncNamespace.Configured("${AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX}u-1"), ns)
        assertEquals(0, remote.calls)
    }

    @Test
    fun pendingRemoteIdIsFetchedPersistedAndProjected() = runTest {
        val remote = FakeRemoteId("u-9")
        val persisted = mutableListOf<Pair<String, String>>()
        val ns = resolver(mapOf("s" to source("s")), mapOf("s" to "tok"), remote, persisted).resolve("s")

        assertEquals(SyncNamespace.Configured("${AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX}u-9"), ns)
        assertEquals(listOf("s" to "u-9"), persisted)
        assertEquals(1, remote.calls)
    }

    @Test
    fun pendingRemoteIdStaysPendingWithoutATokenOrWhenTheFetchFails() = runTest {
        val noToken = resolver(mapOf("s" to source("s")), emptyMap(), FakeRemoteId("u-9")).resolve("s")
        assertEquals(SyncNamespace.PendingRemoteId, noToken)

        val fetchFailed = resolver(mapOf("s" to source("s")), mapOf("s" to "tok"), FakeRemoteId(null)).resolve("s")
        assertEquals(SyncNamespace.PendingRemoteId, fetchFailed)
    }

    @Test
    fun pendingRemoteIdWithNoRegisteredResolverIsLocalOnly() = runTest {
        val ns = resolver(mapOf("s" to source("s")), mapOf("s" to "tok"), remote = null).resolve("s")
        assertIs<SyncNamespace.LocalOnly>(ns)
    }

    @Test
    fun storytellerServiceIsLocalOnlyEvenWithAToken() = runTest {
        val remote = FakeRemoteId("u-9")
        val ns = resolver(
            mapOf("st" to source("st", serverType = ServerType.STORYTELLER_SERVICE)),
            mapOf("st" to "tok"),
            remote,
        ).resolve("st")
        assertIs<SyncNamespace.LocalOnly>(ns)
        assertEquals(0, remote.calls)
    }
}
