package com.riffle.core.data

import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.SourceEntity
import com.riffle.core.database.openRiffleDatabase
import com.riffle.core.domain.AbsWebSourceDescriptor
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.KomgaServerInfoApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Drives the real iOS repository over the real iOS database (#1101). Before this change
 * `getSourceVersion` was `= null` and `ensureSyncNamespace` was the interface default, so both
 * assertions here would fail against the old implementation.
 */
class IosSourceRepositoryImplTest {

    private lateinit var db: RiffleDatabaseAccess

    @BeforeTest
    fun open() {
        db = openRiffleDatabase("riffle-source-repo-test-${NSUUID().UUIDString}.db")
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private val tokens = object : TokenStorage {
        val store = mutableMapOf<String, String>()
        val passwords = mutableMapOf<String, String>()
        override suspend fun saveToken(sourceId: String, token: String) {
            store[sourceId] = token
        }
        override suspend fun getToken(sourceId: String): String? = store[sourceId]
        override suspend fun deleteToken(sourceId: String) {
            store.remove(sourceId)
        }
        override suspend fun savePassword(sourceId: String, password: String) {
            passwords[sourceId] = password
        }
        override suspend fun getPassword(sourceId: String): String? = passwords[sourceId]
        override suspend fun deletePassword(sourceId: String) {
            passwords.remove(sourceId)
        }
    }

    private class FakeAbs(private val version: String?, private val userId: String?) : AbsServerInfoApi {
        override suspend fun getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String? = version
        override suspend fun getCurrentUserId(baseUrl: String, token: String, insecureAllowed: Boolean): String? = userId
    }

    private class FakeKomga(private val version: String?) : KomgaServerInfoApi {
        override suspend fun getServerVersion(baseUrl: String, username: String, password: String, insecureAllowed: Boolean): String? = version
    }

    // The production provider: its io/default are non-Main dispatchers, which is all the
    // repository's `withContext(dispatchers.io)` hop needs here.
    private val dispatchers: DispatcherProvider = IosDispatcherProvider

    private fun repo(abs: AbsServerInfoApi, komga: KomgaServerInfoApi = FakeKomga(null)) = IosSourceRepositoryImpl(
        dao = db.sourceDao(),
        libraryDao = db.libraryDao(),
        tokenStorage = tokens,
        absServerInfoApi = abs,
        komgaServerInfoApi = komga,
        remoteUserIdResolvers = mapOf(
            SourceType.ABS to object : RemoteUserIdResolver {
                override suspend fun resolve(source: Source, token: String): String? =
                    abs.getCurrentUserId(source.url.value, token, source.insecureConnectionAllowed)
            },
        ),
        dispatchers = dispatchers,
    )

    private suspend fun seed(id: String, type: SourceType = SourceType.ABS, serverType: ServerType = ServerType.AUDIOBOOKSHELF) {
        db.sourceDao().upsert(
            SourceEntity(
                id = id,
                url = "https://$id.example.invalid",
                isActive = true,
                insecureConnectionAllowed = false,
                username = "reader",
                serverType = serverType.name,
                type = type.name,
            ),
        )
    }

    @Test
    fun getSourceVersionReturnsTheAbsServerVersion() = runTest {
        seed("abs-1")
        tokens.saveToken("abs-1", "tok")

        assertEquals("2.20.0", repo(FakeAbs(version = "2.20.0", userId = null)).getSourceVersion("abs-1"))
    }

    @Test
    fun getSourceVersionReturnsTheKomgaServerVersion() = runTest {
        seed("k-1", type = SourceType.KOMGA)
        tokens.savePassword("k-1", "pw")

        assertEquals("1.14.1", repo(FakeAbs(null, null), FakeKomga("1.14.1")).getSourceVersion("k-1"))
    }

    @Test
    fun getSourceVersionIsNullForUnknownSourceAndStoryteller() = runTest {
        seed("st-1", serverType = ServerType.STORYTELLER_SERVICE)
        tokens.saveToken("st-1", "tok")
        val repo = repo(FakeAbs(version = "2.20.0", userId = null))

        assertNull(repo.getSourceVersion("st-1"))
        assertNull(repo.getSourceVersion("missing"))
    }

    @Test
    fun ensureSyncNamespaceFetchesAndPersistsTheRemoteUserId() = runTest {
        seed("abs-1")
        tokens.saveToken("abs-1", "tok")
        val repo = repo(FakeAbs(version = null, userId = "user-42"))

        val first = repo.ensureSyncNamespace("abs-1")

        assertEquals(SyncNamespace.Configured("${AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX}user-42"), first)
        assertEquals("user-42", db.sourceDao().getById("abs-1")?.absUserId, "remote id is persisted on the row")
        // A second call is a pure DB lookup: even an API that now fails answers Configured.
        assertEquals(first, repo(FakeAbs(version = null, userId = null)).ensureSyncNamespace("abs-1"))
    }

    @Test
    fun ensureSyncNamespaceIsLocalOnlyForUnknownSource() = runTest {
        val ns = repo(FakeAbs(null, null)).ensureSyncNamespace("missing")
        assertEquals(SyncNamespace.LocalOnly("Unknown source."), ns)
    }
}
