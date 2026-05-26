package com.riffle.core.data

import com.riffle.core.database.ServerDao
import com.riffle.core.database.ServerEntity
import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.NetworkLoginResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryTest {

    private val fakeServerInfoApi = object : AbsServerInfoApi {
        override suspend fun getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String? = null
    }

    private val fakeTokenStorage = object : TokenStorage {
        val tokens = mutableMapOf<String, String>()
        override suspend fun saveToken(serverId: String, token: String) { tokens[serverId] = token }
        override suspend fun getToken(serverId: String) = tokens[serverId]
        override suspend fun deleteToken(serverId: String) { tokens.remove(serverId) }
    }

    private fun fakeDao(vararg initial: ServerEntity): ServerDao = object : ServerDao {
        val store = mutableListOf(*initial)
        override fun observeAll() = flowOf(store.toList())
        override suspend fun getActive() = store.firstOrNull { it.isActive }
        override suspend fun getById(id: String): ServerEntity? = store.firstOrNull { it.id == id }
        override suspend fun upsert(server: ServerEntity) {
            store.removeAll { it.id == server.id }
            store.add(server)
        }
        override suspend fun clearActiveFlag() { store.replaceAll { it.copy(isActive = false) } }
        override suspend fun setActive(id: String) { store.replaceAll { if (it.id == id) it.copy(isActive = true) else it } }
        override suspend fun setActiveAtomic(id: String) { clearActiveFlag(); setActive(id) }
        override suspend fun upsertAsFirstIfNoActive(server: ServerEntity): ServerEntity {
            val toInsert = server.copy(isActive = getActive() == null)
            upsert(toInsert)
            return toInsert
        }
        override suspend fun deleteById(id: String) { store.removeAll { it.id == id } }
    }

    @Test
    fun `addServer success stores token and returns Success`() = runTest {
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.Success("uid-1", "tok-xyz", username = "") }
        val repo = ServerRepositoryImpl(fakeDao(), fakeTokenStorage, fakeApi, fakeServerInfoApi)
        val url = ServerUrl.parse("https://abs.example.com")!!
        val result = repo.addServer(url, "admin", "pass")
        assertTrue(result is AddServerResult.Success)
        assertEquals("tok-xyz", fakeTokenStorage.tokens.values.first())
    }

    @Test
    fun `addServer wrong password returns WrongCredentials`() = runTest {
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("Invalid username or password") }
        val repo = ServerRepositoryImpl(fakeDao(), fakeTokenStorage, fakeApi, fakeServerInfoApi)
        val url = ServerUrl.parse("https://abs.example.com")!!
        val result = repo.addServer(url, "admin", "wrongpass")
        assertTrue(result is AddServerResult.WrongCredentials)
    }

    @Test
    fun `remove deletes server entity and token`() = runTest {
        val entity = ServerEntity("srv-1", "https://abs.example.com", "abs.example.com", true, false, username = "")
        val dao = fakeDao(entity)
        fakeTokenStorage.tokens["srv-1"] = "tok"
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("") }
        val repo = ServerRepositoryImpl(dao, fakeTokenStorage, fakeApi, fakeServerInfoApi)
        repo.remove("srv-1")
        assertTrue("token not deleted", fakeTokenStorage.tokens.isEmpty())
        assertNull("entity not deleted from store", dao.getActive())
    }

    @Test
    fun `first added server is set as active`() = runTest {
        val dao = fakeDao()
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.Success("uid-1", "tok", username = "") }
        val repo = ServerRepositoryImpl(dao, fakeTokenStorage, fakeApi, fakeServerInfoApi)
        val url = ServerUrl.parse("https://abs.example.com")!!
        val result = repo.addServer(url, "admin", "pass")
        assertTrue(result is AddServerResult.Success)
        assertTrue("first server should be active", (result as AddServerResult.Success).server.isActive)
    }

    @Test
    fun `second added server is not active`() = runTest {
        val dao = fakeDao()
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.Success("uid-1", "tok", username = "") }
        val repo = ServerRepositoryImpl(dao, fakeTokenStorage, fakeApi, fakeServerInfoApi)
        repo.addServer(ServerUrl.parse("https://first.example.com")!!, "admin", "pass")
        val result = repo.addServer(ServerUrl.parse("https://second.example.com")!!, "admin", "pass")
        assertTrue(result is AddServerResult.Success)
        assertFalse("second server must not be active", (result as AddServerResult.Success).server.isActive)
    }

    @Test
    fun `addServer returns InsecureConnection when network signals self-signed`() = runTest {
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED) }
        val repo = ServerRepositoryImpl(fakeDao(), fakeTokenStorage, fakeApi, fakeServerInfoApi)
        val url = ServerUrl.parse("https://abs.example.com")!!
        val result = repo.addServer(url, "admin", "pass", insecureAllowed = false)
        assertTrue(result is AddServerResult.InsecureConnection)
    }

    @Test
    fun `setActive changes active server`() = runTest {
        val e1 = ServerEntity("s1", "https://one.example.com", "one.example.com", true, false, username = "")
        val e2 = ServerEntity("s2", "https://two.example.com", "two.example.com", false, false, username = "")
        val dao = fakeDao(e1, e2)
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("") }
        val repo = ServerRepositoryImpl(dao, fakeTokenStorage, fakeApi, fakeServerInfoApi)
        repo.setActive("s2")
        assertEquals("s2", dao.getActive()?.id)
    }
}
