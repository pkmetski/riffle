package com.riffle.core.data.credentialed

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.models.Library
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.PendingSource
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.domain.TokenStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the credential-save-before-row-insert ordering in [CredentialedSourceInstaller.install].
 *
 * The bug this test guards against: `SourceDao.observeAll` is a Room Flow that fires the
 * instant the source row lands. The navigation-drawer's `showDownloadsLink` derivation reads
 * the token from `TokenStorage` synchronously to decide whether the Source supports Downloads.
 * If the installer inserts the row BEFORE saving the token, that first flow emission finds a
 * null token and the drawer hides Downloads — and the later token-save never re-triggers the
 * sources flow, so the link stays hidden until app restart.
 *
 * Reverting the fix (moving `sourceDao.upsertAsFirstIfNoActive` above the token saves in
 * `install`) flips these assertions red.
 */
class CredentialedSourceInstallerOrderingTest {

    @Test
    fun `saveToken runs before source row insert`() = runTest {
        val order = mutableListOf<String>()
        val tokens = RecordingTokenStorage(order)
        val dao = RecordingSourceDao(order)
        val installer = CredentialedSourceInstaller(
            sourceDao = dao,
            libraryDao = NoopLibraryDao,
            tokenStorage = tokens,
            visibilityStore = NoopVisibilityStore,
        )

        val result = installer.install(fakePending(), hiddenLibraryIds = emptySet())

        assertTrue(result is CommitSourceResult.Success)
        // Token + password must be persisted BEFORE the source row lands, so any downstream
        // TokenStorage read triggered by SourceDao.observeAll's first emission already sees them.
        assertEquals(
            listOf("saveToken", "savePassword", "upsertAsFirstIfNoActive"),
            order.filter { it in setOf("saveToken", "savePassword", "upsertAsFirstIfNoActive", "upsert") },
        )
    }

    @Test
    fun `Storyteller commit also saves credentials before upsert`() = runTest {
        val order = mutableListOf<String>()
        val tokens = RecordingTokenStorage(order)
        val dao = RecordingSourceDao(order)
        val installer = CredentialedSourceInstaller(
            sourceDao = dao,
            libraryDao = NoopLibraryDao,
            tokenStorage = tokens,
            visibilityStore = NoopVisibilityStore,
        )

        val pending = fakePending().copy(
            serverType = com.riffle.core.models.ServerType.STORYTELLER_SERVICE,
        )
        val result = installer.install(pending, hiddenLibraryIds = emptySet())

        assertTrue(result is CommitSourceResult.Success)
        // Storyteller takes the non-active path (dao.upsert) but the ordering invariant is the
        // same — credentials first, then the row lands.
        assertEquals(
            listOf("saveToken", "savePassword", "upsert"),
            order.filter { it in setOf("saveToken", "savePassword", "upsertAsFirstIfNoActive", "upsert") },
        )
    }

    private fun fakePending() = PendingSource(
        url = SourceUrl.parse("https://komga.example.com")!!,
        username = "u",
        userId = "",
        token = "Basic dTpw",
        password = "p",
        insecureConnectionAllowed = false,
        libraries = listOf(Library("lib-1", "Comics", "book", false)),
        sourceType = SourceType.KOMGA,
    )

    private class RecordingTokenStorage(private val order: MutableList<String>) : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) { order += "saveToken" }
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) { order += "deleteToken" }
        override suspend fun savePassword(sourceId: String, password: String) { order += "savePassword" }
        override suspend fun getPassword(sourceId: String): String? = null
        override suspend fun deletePassword(sourceId: String) { order += "deletePassword" }
    }

    private class RecordingSourceDao(private val order: MutableList<String>) : SourceDao {
        override fun observeAll(): Flow<List<SourceEntity>> = flowOf(emptyList())
        override suspend fun getActive(): SourceEntity? = null
        override suspend fun getById(id: String): SourceEntity? = null
        override suspend fun getByType(type: String): SourceEntity? = null
        override suspend fun upsert(source: SourceEntity) { order += "upsert" }
        override suspend fun clearActiveFlag() { order += "clearActiveFlag" }
        override suspend fun setActive(id: String) { order += "setActive" }
        override suspend fun setActiveAtomic(id: String) { order += "setActiveAtomic" }
        override suspend fun upsertAsFirstIfNoActive(source: SourceEntity): SourceEntity {
            order += "upsertAsFirstIfNoActive"
            return source.copy(isActive = true)
        }
        override suspend fun deleteById(id: String) { order += "deleteById" }
        override suspend fun setAbsUserId(id: String, absUserId: String) { order += "setAbsUserId" }
    }

    private object NoopLibraryDao : LibraryDao {
        override suspend fun replaceAllForSource(sourceId: String, libraries: List<LibraryEntity>) = Unit
        override suspend fun upsertAll(libraries: List<LibraryEntity>) = Unit
        override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> = flowOf(emptyList())
        override suspend fun libraryIdsForSource(sourceId: String): List<String> = emptyList()
        override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? = null
        override suspend fun deleteBySourceId(sourceId: String) = Unit
        override suspend fun deleteById(sourceId: String, libraryId: String) = Unit
        override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) = Unit
    }

    private object NoopVisibilityStore : LibraryVisibilityPreferencesStore {
        override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun hideLibrary(sourceId: String, libraryId: String) = Unit
        override suspend fun showLibrary(sourceId: String, libraryId: String) = Unit
    }
}
