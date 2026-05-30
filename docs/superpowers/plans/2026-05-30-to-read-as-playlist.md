# To Read as Playlist — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the "To Read" feature off ABS Collections (library-global, shared across accounts) onto ABS Playlists (per-user, server-scoped) so each ABS account has its own independent To Read list. Surface the list as a new "To Read" tab in the library view, sitting between Home and Series.

**Architecture:** Add a parallel Playlist API surface in `core/network` (GET list, POST create, POST add item, DELETE remove item). Replace `ToReadRepositoryImpl`'s internals to hit the playlist endpoints, backed by an in-memory cache keyed by `libraryId` (a `MutableStateFlow<Map<String, ToReadSnapshot>>` holding the playlist id and member item-ids). The cache exposes `observeToReadItemIds(libraryId): Flow<Set<String>>` for reactive UI; the ViewModel combines that with the existing library-items Room cache to produce a `List<LibraryItem>` for the new tab. Optimistic updates: add/remove mutate the cache before the server confirms; on failure the cache reverts. The cache lives only in-memory (no Room migration) — acceptable scope for an asap fix; the tradeoff is that the To Read tab is empty until refreshed once per process lifetime. Collections stay in the codebase as a separate feature. Supersede ADR 0018 with ADR 0019. The pre-existing "To Read" ABS Collection on each user's server is abandoned — the new ADR documents this; users may delete it manually.

**Tech Stack:** Kotlin, Room, OkHttp, kotlinx.serialization, JUnit4. Riffle's standard module layout (`core/network`, `core/data`, `app`).

---

## Background

Discovered: ABS Collections are library-scoped, not user-scoped. Two accounts on the same ABS server share the same "To Read" Collection. Marking a book Read on account A removes it from account B's queue too. See conversation 2026-05-30 for the diagnosis.

ABS Playlists are the only per-user, server-persisted, arbitrary-book-list storage mechanism ABS exposes. They are scoped to `(userId, libraryId)`. The API accepts any library item (no audio-only constraint at the protocol level). The ABS web UI presents playlists with audio-playback affordances — this is cosmetic and acceptable cost for an asap fix.

## ABS endpoint reference

Endpoints verified against the live server at `http://media-server:13378` on 2026-05-30 with the test account. The published docs at api.audiobookshelf.org disagree with the live server on the add/remove paths (docs say `/items` plural; live server returns 404 for plural and 200 for `/item` singular). **Use singular** — that's what the server actually accepts.

- `GET /api/libraries/{libraryId}/playlists?limit=500` — returns the requesting user's playlists in that library. Response shape: `{ results: [{ id, libraryId, userId, name, items: [{ libraryItemId, libraryItem: {...} }] }] }`.
- `POST /api/playlists` — body `{ libraryId, name, items: [{ libraryItemId }] }`. Returns the created playlist. Accepts `items: []` (creates an empty playlist).
- `POST /api/playlists/{id}/item` — body `{ libraryItemId }`. Returns the updated playlist.
- `DELETE /api/playlists/{id}/item/{libraryItemId}` — no body. Returns the updated playlist if items remain.

**Server-side auto-delete:** removing the **last** item from a playlist causes ABS to delete the playlist itself (verified — subsequent `GET /api/playlists/{id}` returns 404). The repository must invalidate its cached `playlistId` whenever a successful `removeFromToRead` leaves the cached `itemIds` empty, so the next `addToToRead` creates a fresh playlist instead of POSTing to a dead id.

The playlist `items[].libraryItem` shape mirrors `AbsCollectionsResponse.AbsCollectionBookDto` for fields we care about: `id`, `libraryId`, `media.metadata.{title, authorName, …}`, `media.ebookFormat`, `media.ebookFile.ino`, `userMediaProgress`.

## File Structure

**New:**
- `core/network/src/main/kotlin/com/riffle/core/network/NetworkPlaylistResult.kt` — `NetworkPlaylist`, `NetworkPlaylistResult`.
- `core/network/src/main/kotlin/com/riffle/core/network/NetworkPlaylistWriteResult.kt` — write result sealed class.
- `core/network/src/main/kotlin/com/riffle/core/network/model/AbsPlaylistsResponse.kt` — wire DTOs + `toNetworkPlaylist()` mapper.
- `core/network/src/main/kotlin/com/riffle/core/network/model/AbsPlaylistWriteRequests.kt` — `AbsCreatePlaylistRequest`, `AbsPlaylistItemRequest`.
- `core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientPlaylistsTest.kt` — MockWebServer tests for GET + writes.
- `docs/adr/0019-to-read-as-playlist.md` — new ADR superseding 0018.

**Modify:**
- `core/network/src/main/kotlin/com/riffle/core/network/AbsLibraryApi.kt` — add 4 playlist methods (mirrors collection signatures).
- `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt` — implement playlist methods.
- `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepository.kt` — replace constant + add `observeToReadItemIds` / `refresh` to interface.
- `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt` — swap internals to playlist API; in-memory cache; optimistic mutations.
- `core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt` — rewrite to drive playlist API and verify in-memory cache behavior.
- `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsViewModel.kt` — expose `toReadItems: StateFlow<List<LibraryItem>>`; refresh in init + on connectivity restored.
- `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt` — add "To Read" tab at index 1; shift Series→2, Collections→3, AllBooks→4; add `ToReadTabContent` composable.
- `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemsViewModelTest.kt` — extend fake `ToReadRepository` + assert tab data flow.
- `docs/adr/0018-to-read-as-named-collection.md` — flip status to "Superseded by ADR 0019".
- `CONTEXT.md` — update the "To Read" glossary entry (mention tab + playlist backing).
- `app/src/androidTest/kotlin/com/riffle/app/harness/StubAbsServer.kt` — add playlist endpoints.
- `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTest.kt` — only if fake-repo wiring breaks; likely needs the two new interface methods stubbed.

---

## Task 1: Add Playlist network DTOs

**Files:**
- Create: `core/network/src/main/kotlin/com/riffle/core/network/NetworkPlaylistResult.kt`
- Create: `core/network/src/main/kotlin/com/riffle/core/network/NetworkPlaylistWriteResult.kt`
- Create: `core/network/src/main/kotlin/com/riffle/core/network/model/AbsPlaylistsResponse.kt`
- Create: `core/network/src/main/kotlin/com/riffle/core/network/model/AbsPlaylistWriteRequests.kt`

- [ ] **Step 1: Create `NetworkPlaylistResult.kt`**

```kotlin
package com.riffle.core.network

data class NetworkPlaylist(
    val id: String,
    val libraryId: String,
    val name: String,
    val items: List<NetworkLibraryItem>,
) {
    val bookCount: Int get() = items.size
}

sealed class NetworkPlaylistResult {
    data class Success(val playlists: List<NetworkPlaylist>) : NetworkPlaylistResult()
    data class NetworkError(val cause: Throwable) : NetworkPlaylistResult()
}
```

- [ ] **Step 2: Create `NetworkPlaylistWriteResult.kt`**

```kotlin
package com.riffle.core.network

sealed class NetworkPlaylistWriteResult {
    data class Success(val playlist: NetworkPlaylist?) : NetworkPlaylistWriteResult()
    data class NetworkError(val cause: Throwable) : NetworkPlaylistWriteResult()
}
```

- [ ] **Step 3: Create `AbsPlaylistsResponse.kt`**

Mirror `AbsCollectionsResponse.kt`. The playlist response has a different shape — items are wrapped in `{ libraryItemId, libraryItem: {...} }` rather than the collection's flat `books: [{...}]`.

```kotlin
package com.riffle.core.network.model

import com.riffle.core.domain.EbookFormat
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkPlaylist
import kotlinx.serialization.Serializable

@Serializable
internal data class AbsPlaylistsResponse(val results: List<AbsPlaylistDto>) {

    @Serializable
    data class AbsPlaylistDto(
        val id: String,
        val libraryId: String,
        val name: String,
        val items: List<AbsPlaylistItemDto> = emptyList(),
    )

    @Serializable
    data class AbsPlaylistItemDto(
        val libraryItemId: String,
        val libraryItem: AbsPlaylistLibraryItemDto? = null,
    )

    @Serializable
    data class AbsPlaylistLibraryItemDto(
        val id: String,
        val libraryId: String,
        val media: AbsPlaylistMediaDto,
        val userMediaProgress: AbsPlaylistProgressDto? = null,
    )

    @Serializable
    data class AbsPlaylistMediaDto(
        val metadata: AbsPlaylistMetadataDto,
        val ebookFormat: String? = null,
        val ebookFile: AbsPlaylistEbookFileDto? = null,
    )

    @Serializable
    data class AbsPlaylistEbookFileDto(val ino: String = "")

    @Serializable
    data class AbsPlaylistMetadataDto(
        val title: String = "",
        val authorName: String = "",
        val description: String? = null,
        val seriesName: String? = null,
        val publishedYear: String? = null,
        val genres: List<String> = emptyList(),
        val publisher: String? = null,
    )

    @Serializable
    data class AbsPlaylistProgressDto(
        val progress: Float = 0f,
        val ebookProgress: Float? = null,
    )
}

internal fun AbsPlaylistsResponse.AbsPlaylistDto.toNetworkPlaylist(): NetworkPlaylist =
    NetworkPlaylist(
        id = id,
        libraryId = libraryId,
        name = name,
        items = items.mapNotNull { entry ->
            val li = entry.libraryItem ?: return@mapNotNull null
            val progress = li.userMediaProgress?.ebookProgress
                ?: li.userMediaProgress?.progress
            NetworkLibraryItem(
                id = li.id,
                libraryId = li.libraryId,
                title = li.media.metadata.title,
                author = li.media.metadata.authorName,
                readingProgress = progress,
                ebookFormat = EbookFormat.from(li.media.ebookFormat),
                ebookFileIno = li.media.ebookFile?.ino?.takeIf { it.isNotEmpty() },
                description = li.media.metadata.description,
                seriesName = li.media.metadata.seriesName,
                publishedYear = li.media.metadata.publishedYear,
                genres = li.media.metadata.genres,
                publisher = li.media.metadata.publisher,
            )
        },
    )
```

- [ ] **Step 4: Create `AbsPlaylistWriteRequests.kt`**

```kotlin
package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsCreatePlaylistRequest(
    val libraryId: String,
    val name: String,
    val items: List<AbsPlaylistItemRequest>,
)

@Serializable
internal data class AbsPlaylistItemRequest(
    val libraryItemId: String,
)
```

- [ ] **Step 5: Compile**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/NetworkPlaylistResult.kt \
        core/network/src/main/kotlin/com/riffle/core/network/NetworkPlaylistWriteResult.kt \
        core/network/src/main/kotlin/com/riffle/core/network/model/AbsPlaylistsResponse.kt \
        core/network/src/main/kotlin/com/riffle/core/network/model/AbsPlaylistWriteRequests.kt
git commit -m "feat(network): add Playlist DTOs and result types"
```

---

## Task 2: Extend `AbsLibraryApi` interface with playlist methods

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsLibraryApi.kt`

- [ ] **Step 1: Add four playlist methods**

Append below `removeBookFromCollection`:

```kotlin
    suspend fun getPlaylists(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistResult = throw UnsupportedOperationException("getPlaylists not implemented")

    suspend fun createPlaylist(
        baseUrl: String,
        libraryId: String,
        name: String,
        initialBookId: String?,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult = throw UnsupportedOperationException("createPlaylist not implemented")

    suspend fun addBookToPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult = throw UnsupportedOperationException("addBookToPlaylist not implemented")

    suspend fun removeBookFromPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult = throw UnsupportedOperationException("removeBookFromPlaylist not implemented")
```

- [ ] **Step 2: Compile**

Run: `./gradlew :core:network:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/AbsLibraryApi.kt
git commit -m "feat(network): add playlist methods to AbsLibraryApi"
```

---

## Task 3: Implement playlist methods in `AbsApiClient` (TDD via MockWebServer)

**Files:**
- Test: `core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientPlaylistsTest.kt`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`

> **Implementer note:** At execution time, open the existing `AbsApiClientCollectionsWriteTest.kt` and mirror its setup style for MockWebServer + httpClient construction. The test below assumes the same pattern.

- [ ] **Step 1: Write the failing test for `getPlaylists`**

> Endpoint shapes are already verified against the live server (see "ABS endpoint reference" at the top of this plan). Use `/item` singular for add/remove.

Create `AbsApiClientPlaylistsTest.kt` following the layout of `AbsApiClientSeriesCollectionTest.kt`. Cover at minimum:

```kotlin
@Test
fun `getPlaylists returns playlists parsed from response`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody(PLAYLISTS_JSON))
    val result = runBlocking {
        client.getPlaylists(server.url("").toString().trimEnd('/'), "lib-1", "tok", insecureAllowed = false)
    }
    val recorded = server.takeRequest()
    assertEquals("GET", recorded.method)
    assertEquals("/api/libraries/lib-1/playlists?limit=500", recorded.path)
    assertEquals("Bearer tok", recorded.getHeader("Authorization"))
    assertTrue(result is NetworkPlaylistResult.Success)
    val playlists = (result as NetworkPlaylistResult.Success).playlists
    assertEquals(1, playlists.size)
    assertEquals("pl-1", playlists[0].id)
    assertEquals("To Read", playlists[0].name)
    assertEquals(listOf("item-1"), playlists[0].items.map { it.id })
}

private companion object {
    const val PLAYLISTS_JSON = """
    {"results":[{
      "id":"pl-1","libraryId":"lib-1","name":"To Read","items":[
        {"libraryItemId":"item-1","libraryItem":{
          "id":"item-1","libraryId":"lib-1",
          "media":{"metadata":{"title":"Book","authorName":"A"},"ebookFormat":"epub"}
        }}
      ]
    }]}
    """
}
```

Add equivalent tests for `createPlaylist` (POST body shape), `addBookToPlaylist` (path + body), `removeBookFromPlaylist` (DELETE path). Use `AbsApiClientCollectionsWriteTest.kt` for the assertion idiom.

- [ ] **Step 2: Run tests — verify they fail**

Run: `./gradlew :core:network:test --tests "com.riffle.core.network.AbsApiClientPlaylistsTest"`
Expected: failures with `UnsupportedOperationException("getPlaylists not implemented")` etc.

- [ ] **Step 3: Implement the four methods in `AbsApiClient`**

Insert after the collection implementations (around line 297 of `AbsApiClient.kt`):

```kotlin
    override suspend fun getPlaylists(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/libraries/$libraryId/playlists?limit=500")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string() ?: return@withContext NetworkPlaylistResult.NetworkError(
                IOException("Empty response body")
            )
            val parsed = json.decodeFromString<AbsPlaylistsResponse>(raw)
            NetworkPlaylistResult.Success(parsed.results.map { it.toNetworkPlaylist() })
        } catch (e: Exception) {
            NetworkPlaylistResult.NetworkError(e)
        }
    }

    override suspend fun createPlaylist(
        baseUrl: String,
        libraryId: String,
        name: String,
        initialBookId: String?,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult {
        val payload = AbsCreatePlaylistRequest(
            libraryId = libraryId,
            name = name,
            items = listOfNotNull(initialBookId?.let { AbsPlaylistItemRequest(it) }),
        )
        val body = json.encodeToString(AbsCreatePlaylistRequest.serializer(), payload)
            .toRequestBody(jsonMediaType)
        return executePlaylistWrite(
            url = "$baseUrl/api/playlists",
            token = token,
            insecureAllowed = insecureAllowed,
        ) { post(body) }
    }

    override suspend fun addBookToPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult {
        val body = json.encodeToString(
            AbsPlaylistItemRequest.serializer(),
            AbsPlaylistItemRequest(libraryItemId),
        ).toRequestBody(jsonMediaType)
        return executePlaylistWrite(
            url = "$baseUrl/api/playlists/$playlistId/item",
            token = token,
            insecureAllowed = insecureAllowed,
        ) { post(body) }
    }

    override suspend fun removeBookFromPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult = executePlaylistWrite(
        url = "$baseUrl/api/playlists/$playlistId/item/$libraryItemId",
        token = token,
        insecureAllowed = insecureAllowed,
    ) { delete() }

    private suspend fun executePlaylistWrite(
        url: String,
        token: String,
        insecureAllowed: Boolean,
        buildRequest: Request.Builder.() -> Unit,
    ): NetworkPlaylistWriteResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .apply { buildRequest() }
            .build()
        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext NetworkPlaylistWriteResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            val playlist = if (raw.isBlank()) null else
                json.decodeFromString(AbsPlaylistsResponse.AbsPlaylistDto.serializer(), raw).toNetworkPlaylist()
            NetworkPlaylistWriteResult.Success(playlist)
        } catch (e: IOException) {
            NetworkPlaylistWriteResult.NetworkError(e)
        }
    }
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `./gradlew :core:network:test --tests "com.riffle.core.network.AbsApiClientPlaylistsTest"`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 5: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt \
        core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientPlaylistsTest.kt
git commit -m "feat(network): implement playlist endpoints on AbsApiClient"
```

---

## Task 4: Rewrite `ToReadRepository` with in-memory cache + observable Flow (TDD)

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepository.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt`

> **Design note:** The interface gains `observeToReadItemIds(libraryId): Flow<Set<String>>` and `refresh(libraryId): Boolean`. Implementation holds a `MutableStateFlow<Map<String, ToReadSnapshot>>` where `ToReadSnapshot(playlistId, itemIds)`. `refresh` performs `GET /api/libraries/:id/playlists`, finds the To Read playlist, and updates the snapshot. `isInToRead` reads from the cache (callers are responsible for calling `refresh` first — the ViewModel does this). Add/remove mutate the cache optimistically before the network call, and revert on failure. Cache is in-memory only — no Room table, no migration. Tradeoff: To Read tab is empty until the first refresh in each process; that refresh fires from `LibraryItemsViewModel.init`.

- [ ] **Step 1: Update `ToReadRepository.kt` interface**

Replace the file contents:

```kotlin
package com.riffle.core.data

import kotlinx.coroutines.flow.Flow

const val TO_READ_PLAYLIST_NAME = "To Read"

/**
 * Manages the per-Library, per-User "To Read" Playlist on the active ABS server.
 *
 * Backed by a normal ABS Playlist named [TO_READ_PLAYLIST_NAME], looked up by name and
 * find-or-created on first use. See ADR 0019.
 *
 * Playlists are scoped to (userId, libraryId) on the server, so each ABS account has its
 * own independent To Read list.
 *
 * Cache: in-memory only. Call [refresh] once per library to populate before relying on
 * [observeToReadItemIds] or [isInToRead] — typically from `LibraryItemsViewModel.init`.
 */
interface ToReadRepository {
    /** Item-ids currently in the To Read playlist for [libraryId]. Empty before first refresh. */
    fun observeToReadItemIds(libraryId: String): Flow<Set<String>>

    /** Fetches the To Read playlist from the server and refreshes the in-memory cache. */
    suspend fun refresh(libraryId: String): Boolean

    suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean
    suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean
    suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean
}
```

- [ ] **Step 2: Rewrite `ToReadRepositoryTest.kt`**

Replace the file. The tests drive the fake `AbsLibraryApi` directly and exercise the in-memory cache via `refresh` + `observeToReadItemIds`. Cover: refresh populates cache; isInToRead reads cache (no implicit network); add updates cache optimistically + reverts on failure; remove same; create-when-missing path. The full replacement:

```kotlin
package com.riffle.core.data

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkLibrariesResult
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkLibraryItemsResult
import com.riffle.core.network.NetworkPlaylist
import com.riffle.core.network.NetworkPlaylistResult
import com.riffle.core.network.NetworkPlaylistWriteResult
import com.riffle.core.network.NetworkSeriesResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ToReadRepositoryTest {

    private val activeServer = Server(
        id = "s1",
        url = ServerUrl.parse("http://abs.local")!!,
        displayName = "ABS",
        isActive = true,
        insecureConnectionAllowed = false,
        username = "u",
    )

    private fun makeRepo(
        api: AbsLibraryApi = FakeAbsApi(),
        serverRepository: ServerRepository = FakeServerRepository(activeServer),
        tokenStorage: TokenStorage = FakeTokenStorage(mutableMapOf("s1" to "tok")),
    ) = ToReadRepositoryImpl(api, serverRepository, tokenStorage)

    // ── refresh + observeToReadItemIds ────────────────────────────────────────

    @Test
    fun `refresh populates cache from server`() = runTest {
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1", "item-2")))),
        )
        val repo = makeRepo(api)
        assertTrue(repo.refresh("lib-1"))
        assertEquals(setOf("item-1", "item-2"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `refresh populates empty when no To Read playlist exists`() = runTest {
        val api = FakeAbsApi(playlistsByLibrary = mapOf("lib-1" to emptyList()))
        val repo = makeRepo(api)
        assertTrue(repo.refresh("lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `refresh returns false on network error and leaves cache untouched`() = runTest {
        val api = object : FakeAbsApi() {
            override suspend fun getPlaylists(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkPlaylistResult =
                NetworkPlaylistResult.NetworkError(IOException("HTTP 500"))
        }
        val repo = makeRepo(api)
        assertFalse(repo.refresh("lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    // ── isInToRead ────────────────────────────────────────────────────────────

    @Test
    fun `isInToRead reads from cache after refresh`() = runTest {
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))),
        )
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.isInToRead("item-1", "lib-1"))
        assertFalse(repo.isInToRead("item-9", "lib-1"))
    }

    @Test
    fun `isInToRead returns false before any refresh`() = runTest {
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))),
        )
        assertFalse(makeRepo(api).isInToRead("item-1", "lib-1"))
    }

    // ── addToToRead ───────────────────────────────────────────────────────────

    @Test
    fun `addToToRead appends to existing playlist and updates cache optimistically`() = runTest {
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", emptyList()))),
        )
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.addToToRead("item-1", "lib-1"))
        assertTrue(api.createCalls.isEmpty())
        assertEquals(listOf("pl-A" to "item-1"), api.addCalls)
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead creates playlist when cache is empty + no playlist on server`() = runTest {
        val api = FakeAbsApi(playlistsByLibrary = mapOf("lib-1" to emptyList()))
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.addToToRead("item-1", "lib-1"))
        assertEquals(listOf(Triple("lib-1", "To Read", "item-1")), api.createCalls)
        assertTrue(api.addCalls.isEmpty())
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead returns false when no active server`() = runTest {
        val repo = makeRepo(
            serverRepository = FakeServerRepository(activeServer = null),
            tokenStorage = FakeTokenStorage(mutableMapOf()),
        )
        assertFalse(repo.addToToRead("item-1", "lib-1"))
    }

    @Test
    fun `addToToRead reverts cache when add fails`() = runTest {
        val api = object : FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", emptyList()))),
        ) {
            override suspend fun addBookToPlaylist(
                baseUrl: String, playlistId: String, libraryItemId: String,
                token: String, insecureAllowed: Boolean,
            ): NetworkPlaylistWriteResult = NetworkPlaylistWriteResult.NetworkError(IOException("HTTP 500"))
        }
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertFalse(repo.addToToRead("item-1", "lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead reverts cache when create fails`() = runTest {
        val api = object : FakeAbsApi(playlistsByLibrary = mapOf("lib-1" to emptyList())) {
            override suspend fun createPlaylist(
                baseUrl: String, libraryId: String, name: String, initialBookId: String?,
                token: String, insecureAllowed: Boolean,
            ): NetworkPlaylistWriteResult = NetworkPlaylistWriteResult.NetworkError(IOException("HTTP 500"))
        }
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertFalse(repo.addToToRead("item-1", "lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    // ── removeFromToRead ──────────────────────────────────────────────────────

    @Test
    fun `removeFromToRead calls DELETE and updates cache`() = runTest {
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))),
        )
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        assertEquals(listOf("pl-A" to "item-1"), api.removeCalls)
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `removeFromToRead clears cached playlistId when last item is removed`() = runTest {
        // ABS auto-deletes empty playlists server-side, so the cached id must be invalidated
        // or the next add will POST to a dead playlist id.
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))),
        )
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        // Next add must create a new playlist, not POST to the dead pl-A.
        assertTrue(repo.addToToRead("item-2", "lib-1"))
        assertEquals(listOf(Triple("lib-1", "To Read", "item-2")), api.createCalls)
        assertTrue(api.addCalls.isEmpty())
    }

    @Test
    fun `removeFromToRead returns true and makes no call when cache empty`() = runTest {
        val api = FakeAbsApi(playlistsByLibrary = mapOf("lib-1" to emptyList()))
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        assertTrue(api.removeCalls.isEmpty())
    }

    @Test
    fun `removeFromToRead reverts cache when DELETE fails`() = runTest {
        val api = object : FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))),
        ) {
            override suspend fun removeBookFromPlaylist(
                baseUrl: String, playlistId: String, libraryItemId: String,
                token: String, insecureAllowed: Boolean,
            ): NetworkPlaylistWriteResult = NetworkPlaylistWriteResult.NetworkError(IOException("HTTP 500"))
        }
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertFalse(repo.removeFromToRead("item-1", "lib-1"))
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun playlist(id: String, name: String, itemIds: List<String>) = NetworkPlaylist(
        id = id, libraryId = "lib-1", name = name,
        items = itemIds.map { NetworkLibraryItem(
            id = it, libraryId = "lib-1", title = "T", author = "A",
            readingProgress = 0f, ebookFormat = EbookFormat.Epub,
        ) },
    )
}

private class FakeServerRepository(private val activeServer: Server?) : ServerRepository {
    override fun observeAll() = MutableStateFlow(listOfNotNull(activeServer))
    override suspend fun getActive(): Server? = activeServer
    override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean): AuthenticateResult =
        AuthenticateResult.NetworkError(IOException())
    override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult =
        CommitServerResult.Failure(IOException())
    override suspend fun setActive(serverId: String) {}
    override suspend fun remove(serverId: String) {}
    override suspend fun getServerVersion(serverId: String): String? = null
}

private class FakeTokenStorage(private val tokens: MutableMap<String, String>) : TokenStorage {
    override suspend fun saveToken(serverId: String, token: String) { tokens[serverId] = token }
    override suspend fun getToken(serverId: String): String? = tokens[serverId]
    override suspend fun deleteToken(serverId: String) { tokens.remove(serverId) }
}

private open class FakeAbsApi(
    val playlistsByLibrary: Map<String, List<NetworkPlaylist>> = emptyMap(),
) : AbsLibraryApi {
    val createCalls = mutableListOf<Triple<String, String, String?>>()
    val addCalls = mutableListOf<Pair<String, String>>()
    val removeCalls = mutableListOf<Pair<String, String>>()

    override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkLibrariesResult =
        NetworkLibrariesResult.Success(emptyList())

    override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkLibraryItemsResult =
        NetworkLibraryItemsResult.Success(emptyList())

    override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkSeriesResult =
        NetworkSeriesResult.Success(emptyList())

    override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
        com.riffle.core.network.NetworkCollectionResult.Success(emptyList())

    override suspend fun getPlaylists(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkPlaylistResult =
        NetworkPlaylistResult.Success(playlistsByLibrary[libraryId].orEmpty())

    override suspend fun createPlaylist(
        baseUrl: String, libraryId: String, name: String, initialBookId: String?,
        token: String, insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult {
        createCalls += Triple(libraryId, name, initialBookId)
        return NetworkPlaylistWriteResult.Success(NetworkPlaylist("pl-new", libraryId, name, emptyList()))
    }

    override suspend fun addBookToPlaylist(
        baseUrl: String, playlistId: String, libraryItemId: String,
        token: String, insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult {
        addCalls += playlistId to libraryItemId
        return NetworkPlaylistWriteResult.Success(null)
    }

    override suspend fun removeBookFromPlaylist(
        baseUrl: String, playlistId: String, libraryItemId: String,
        token: String, insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult {
        removeCalls += playlistId to libraryItemId
        return NetworkPlaylistWriteResult.Success(null)
    }
}
```

- [ ] **Step 3: Run tests — verify they fail**

Run: `./gradlew :core:data:test --tests "com.riffle.core.data.ToReadRepositoryTest"`
Expected: compilation errors (`TO_READ_COLLECTION_NAME` removed, `ToReadRepositoryImpl` constructor doesn't match) and/or test failures.

- [ ] **Step 4: Rewrite `ToReadRepositoryImpl.kt`**

Replace the file:

```kotlin
package com.riffle.core.data

import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkPlaylistResult
import com.riffle.core.network.NetworkPlaylistWriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory To Read snapshot for a single library.
 *
 * `playlistId == null` means we know the server has no To Read playlist (so next add must create).
 * After [ToReadRepositoryImpl.refresh] succeeds, the snapshot reflects the server's state.
 */
private data class ToReadSnapshot(val playlistId: String?, val itemIds: Set<String>)

@Singleton
class ToReadRepositoryImpl @Inject constructor(
    private val api: AbsLibraryApi,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : ToReadRepository {

    private val cache = MutableStateFlow<Map<String, ToReadSnapshot>>(emptyMap())

    override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> =
        cache.map { it[libraryId]?.itemIds ?: emptySet() }

    override suspend fun refresh(libraryId: String): Boolean {
        val session = resolveSession() ?: return false
        val result = api.getPlaylists(session.baseUrl, libraryId, session.token, session.insecureAllowed)
        if (result !is NetworkPlaylistResult.Success) return false
        val match = result.playlists.firstOrNull { it.name == TO_READ_PLAYLIST_NAME }
        val snapshot = ToReadSnapshot(
            playlistId = match?.id,
            itemIds = match?.items?.map { it.id }?.toSet() ?: emptySet(),
        )
        cache.value = cache.value + (libraryId to snapshot)
        return true
    }

    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean =
        cache.value[libraryId]?.itemIds?.contains(libraryItemId) == true

    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
        val session = resolveSession() ?: return false
        val before = cache.value[libraryId] ?: ToReadSnapshot(playlistId = null, itemIds = emptySet())
        // Optimistic update
        cache.value = cache.value + (libraryId to before.copy(itemIds = before.itemIds + libraryItemId))
        val playlistId = before.playlistId
        val ok = if (playlistId == null) {
            val r = api.createPlaylist(
                session.baseUrl, libraryId, TO_READ_PLAYLIST_NAME, libraryItemId,
                session.token, session.insecureAllowed,
            )
            if (r is NetworkPlaylistWriteResult.Success) {
                val newId = r.playlist?.id
                if (newId != null) {
                    cache.value = cache.value + (libraryId to ToReadSnapshot(newId, before.itemIds + libraryItemId))
                }
                true
            } else false
        } else {
            val r = api.addBookToPlaylist(
                session.baseUrl, playlistId, libraryItemId, session.token, session.insecureAllowed,
            )
            r is NetworkPlaylistWriteResult.Success
        }
        if (!ok) cache.value = cache.value + (libraryId to before)
        return ok
    }

    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
        val session = resolveSession() ?: return false
        val before = cache.value[libraryId] ?: return true
        val playlistId = before.playlistId ?: return true
        if (libraryItemId !in before.itemIds) return true
        val remainingIds = before.itemIds - libraryItemId
        // Optimistic update. If we're removing the last item, ABS auto-deletes the playlist
        // server-side — drop our cached playlistId so the next addToToRead creates a fresh one.
        val optimistic = if (remainingIds.isEmpty()) {
            ToReadSnapshot(playlistId = null, itemIds = emptySet())
        } else {
            before.copy(itemIds = remainingIds)
        }
        cache.value = cache.value + (libraryId to optimistic)
        val r = api.removeBookFromPlaylist(
            session.baseUrl, playlistId, libraryItemId, session.token, session.insecureAllowed,
        )
        val ok = r is NetworkPlaylistWriteResult.Success
        if (!ok) cache.value = cache.value + (libraryId to before)
        return ok
    }

    private suspend fun resolveSession(): Session? {
        val server = serverRepository.getActive() ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        return Session(server.url.value, token, server.insecureConnectionAllowed)
    }

    private data class Session(val baseUrl: String, val token: String, val insecureAllowed: Boolean)
}
```

- [ ] **Step 5: Run tests — verify they pass**

Run: `./gradlew :core:data:test --tests "com.riffle.core.data.ToReadRepositoryTest"`
Expected: all tests pass.

- [ ] **Step 6: Compile + run all core:data tests**

Run: `./gradlew :core:data:test`
Expected: BUILD SUCCESSFUL. Fix any unrelated compile breakage caused by `LibraryRepository` no longer being needed.

- [ ] **Step 7: Update DI if needed**

Open `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`. If the binding for `ToReadRepository` listed `LibraryRepository` explicitly, no change is needed (Hilt will resolve constructor injection). Just verify the project builds:

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/ToReadRepository.kt \
        core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt
git commit -m "feat(data): back To Read with ABS Playlists instead of Collections"
```

---

## Task 5: Wire `ToReadRepository.refresh` into both ViewModels

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt`
- Modify: `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemsViewModelTest.kt`
- Modify: `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTest.kt` (only to extend the fake `ToReadRepository`)

> **Design note:** The new `ToReadRepository.isInToRead` reads from the in-memory cache and returns false before the first `refresh`. To keep parity with previous behavior, both ViewModels call `refresh(libraryId)` early:
> - `LibraryItemsViewModel.init` — alongside `libraryRepository.refresh*` calls, and again on connectivity restored.
> - `LibraryItemDetailViewModel` — call `refresh(item.libraryId)` before reading `isInToRead` when constructing the initial UI state.

- [ ] **Step 1: Add `ToReadRepository` to `LibraryItemsViewModel` and expose `toReadItems`**

Constructor: inject `toReadRepository: ToReadRepository`.

Add fields below the existing `allBooks` state flow:

```kotlin
    val toReadItemIds: StateFlow<Set<String>> = toReadRepository.observeToReadItemIds(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val toReadItems: StateFlow<List<LibraryItem>> = combine(toReadItemIds, allBooks, isOffline) { ids, all, offline ->
        val byId = all.associateBy { it.id }
        val items = ids.mapNotNull { byId[it] }
        if (offline) items.filter { isAvailableOffline(it) } else items
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

> Reusing `allBooks` (the existing flow of every book in the library) gives us full `LibraryItem` objects with `coverUrl`, `isCached`, `isDownloaded` for free.

- [ ] **Step 2: Refresh the To Read playlist in `LibraryItemsViewModel`**

In `init`, alongside the existing `refresh()` call, also kick off:

```kotlin
        viewModelScope.launch { toReadRepository.refresh(libraryId) }
```

In the existing connectivity-restored block, also call `toReadRepository.refresh(libraryId)`.

In the existing `fun refresh()` method, add `toReadRepository.refresh(libraryId)` as a fourth deferred call alongside `itemsDeferred`, `seriesDeferred`, `collectionsDeferred`. A network failure here should NOT flip the offline banner (To Read failures are non-critical).

- [ ] **Step 3: Update `LibraryItemDetailViewModel` to refresh before reading**

Find the existing line `val isInToRead = toReadRepository.isInToRead(item.id, item.libraryId)` and replace with:

```kotlin
                    toReadRepository.refresh(item.libraryId)
                    val isInToRead = toReadRepository.isInToRead(item.id, item.libraryId)
```

- [ ] **Step 4: Extend the test fakes**

In `LibraryItemDetailViewModelTest.kt`, the in-test `FakeToReadRepository` (or however the test stubs `ToReadRepository`) needs the two new methods:

```kotlin
override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
override suspend fun refresh(libraryId: String): Boolean = true
```

In `LibraryItemsViewModelTest.kt`, do the same — provide a fake that returns a controllable `MutableStateFlow<Set<String>>` for `observeToReadItemIds`.

Add one new test in `LibraryItemsViewModelTest.kt` asserting `toReadItems` is the intersection of `toReadItemIds` and `allBooks`. Mirror the style of the existing `filteredCollections` test if there is one.

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*LibraryItemsViewModelTest*" --tests "*LibraryItemDetailViewModelTest*"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt \
        app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemsViewModelTest.kt \
        app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTest.kt
git commit -m "feat(library): expose To Read items + refresh through ViewModels"
```

---

## Task 6: Add "To Read" tab to the library screen

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt`

> **Design:** Tab order becomes Home (0), **To Read (1, new)**, Series (2), Collections (3), All Books (4). The To Read tab content is a grid of book covers — reuse the existing `BookSectionGrid` (or whatever composable `AllBooksTabContent` uses for its grid). Empty state: centered "Nothing in To Read" message.

- [ ] **Step 1: Add the tab to `LibraryTabBar`**

In the `LibraryTabBar` composable (around line 809), insert a new `NavigationBarItem` at index 1, between Home and Series. Use `Icons.Filled.Bookmark` for the icon (import `androidx.compose.material.icons.filled.Bookmark`). Shift the existing items' indices: Series 1→2, Collections 2→3, All Books 3→4. The full updated composable:

```kotlin
@Composable
private fun LibraryTabBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            icon = { Icon(Icons.Filled.Bookmark, contentDescription = "To Read") },
        )
        NavigationBarItem(
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
            icon = { Icon(Icons.Filled.FormatListNumbered, contentDescription = "Series") },
        )
        NavigationBarItem(
            selected = selectedTab == 3,
            onClick = { onTabSelected(3) },
            icon = { Icon(Icons.Filled.Folder, contentDescription = "Collections") },
        )
        NavigationBarItem(
            selected = selectedTab == 4,
            onClick = { onTabSelected(4) },
            icon = { Icon(Icons.Filled.GridView, contentDescription = "All Books") },
        )
    }
}
```

- [ ] **Step 2: Add the `when` branch for the new tab**

Inside `LibraryItemsScreen`, in the `when (selectedTab)` block (around line 171), shift the existing branches and insert the new one:

```kotlin
                when (selectedTab) {
                    0 -> HomeTabContent(
                        inProgress = inProgress,
                        recentlyAdded = recentlyAdded,
                        finished = finished,
                        isLoading = isLoading,
                        token = viewModel.authToken,
                        onItemSelected = onItemSelected,
                        onSectionSeeMore = onSectionSeeMore,
                    )
                    1 -> ToReadTabContent(
                        items = toReadItems,
                        isLoading = isLoading,
                        token = viewModel.authToken,
                        onItemSelected = onItemSelected,
                    )
                    2 -> SeriesTabContent(
                        items = series,
                        isLoading = isLoading,
                        token = viewModel.authToken,
                        onSeriesSelected = onSeriesSelected,
                    )
                    3 -> CollectionsTabContent(
                        items = collections,
                        isLoading = isLoading,
                        token = viewModel.authToken,
                        collectionCoverUrls = collectionCoverUrls,
                        onCollectionSelected = onCollectionSelected,
                    )
                    4 -> AllBooksTabContent(
                        items = allBooks,
                        isLoading = isLoading,
                        token = viewModel.authToken,
                        onItemSelected = onItemSelected,
                    )
                    else -> {}
                }
```

Higher up where `allBooks`, `series`, etc. are collected from the ViewModel, also collect `toReadItems`:

```kotlin
    val toReadItems by viewModel.toReadItems.collectAsState()
```

- [ ] **Step 3: Add the `ToReadTabContent` composable**

Add next to `AllBooksTabContent`. Mirror `AllBooksTabContent`'s structure — grid of book covers, same paddings and click handler. Replace the empty-state text with "Nothing in To Read".

```kotlin
@Composable
private fun ToReadTabContent(
    items: List<LibraryItem>,
    isLoading: Boolean,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
) {
    if (isLoading) return
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing in To Read")
        }
        return
    }
    // Copy the body of AllBooksTabContent verbatim — same LazyVerticalGrid layout.
    AllBooksTabContent(items = items, isLoading = false, token = token, onItemSelected = onItemSelected)
}
```

> If `AllBooksTabContent`'s empty-state text is hard-coded inside it, just inline the grid here instead of delegating. The grid is small enough.

- [ ] **Step 4: Build + visual sanity check**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Install on a device or emulator. Open a library. Confirm:
- Bottom tab bar has 5 tabs (Home, bookmark icon, Series, Collections, All Books).
- Tapping the bookmark tab shows the current user's To Read items, or "Nothing in To Read" if empty.
- Tapping an item in the To Read tab navigates to the detail screen.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt
git commit -m "feat(library): add To Read tab between Home and Series"
```

---

## Task 7: Wire the stub server playlist endpoints (harness)

**Files:**
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/StubAbsServer.kt`

> Open the file at execution time to see the actual handler dispatch style. The instructions below describe the shape of the change; mirror the existing collection handlers.

- [ ] **Step 1: Add playlist constants and JSON response**

Add near `TEST_COLLECTION_ID`:

```kotlin
const val TEST_PLAYLIST_ID = "playlist-test-1"
const val TEST_PLAYLIST_NAME = "To Read"
```

- [ ] **Step 2: Add request handlers**

In the request-dispatching block (around the `request.path == "/api/libraries/$TEST_LIBRARY_ID/collections..."` line), add:

```kotlin
request.path == "/api/libraries/$TEST_LIBRARY_ID/playlists?limit=500" -> playlistsResponse()
request.path == "/api/playlists" && request.method == "POST" -> playlistCreateResponse()
request.path?.matches(Regex("/api/playlists/[^/]+/item")) == true && request.method == "POST" -> playlistItemAddResponse()
request.path?.matches(Regex("/api/playlists/[^/]+/item/[^/]+")) == true && request.method == "DELETE" -> playlistItemRemoveResponse()
```

Provide minimal JSON bodies — for current harness tests, the simplest response is an empty playlists list (`{"results":[]}`) plus 200 OK for writes. Add fixture data only if a harness test needs To Read state to be present.

- [ ] **Step 3: Run harness tests**

Run: `make harness-test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/kotlin/com/riffle/app/harness/StubAbsServer.kt
git commit -m "test(harness): stub playlist endpoints"
```

---

## Task 8: Write ADR 0019 and supersede ADR 0018

**Files:**
- Create: `docs/adr/0019-to-read-as-playlist.md`
- Modify: `docs/adr/0018-to-read-as-named-collection.md`
- Modify: `CONTEXT.md`

- [ ] **Step 1: Write ADR 0019**

Create `docs/adr/0019-to-read-as-playlist.md`:

```markdown
# ADR 0019 — "To Read" implemented as a name-matched ABS Playlist

**Status:** Accepted (supersedes ADR 0018)

## Context

ADR 0018 implemented "To Read" as an ABS Collection named `To Read`, looked up by name. After shipping we observed that ABS Collections are **library-scoped, not user-scoped**: the `GET /api/libraries/:id/collections` endpoint returns the same collections to every authenticated user, and mutations are visible to all users with access to that library. Two accounts on the same server therefore shared a single "To Read" list, and the ADR-0018 Read→remove-from-To-Read invariant leaked across users.

ABS has one per-user, server-persisted, arbitrary-book-list mechanism: **Playlists**. Playlists are scoped to `(userId, libraryId)`. The API endpoints (`GET /api/libraries/:id/playlists`, `POST /api/playlists`, `POST /api/playlists/:id/item`, `DELETE /api/playlists/:id/item/:libraryItemId`) accept any library item, including ebook-only items.

## Decision

**Implement "To Read" as a regular ABS Playlist named `To Read`, looked up by name and find-or-created on first use.** The find-or-create-by-name pattern from ADR 0018 carries over unchanged; only the backing storage flips from Collection to Playlist.

The Read→remove-from-To-Read invariant (ADR 0018 rule 2) is preserved against the playlist instead of the collection.

In-memory cache only (no Room table). A `MutableStateFlow<Map<libraryId, ToReadSnapshot>>` in `ToReadRepositoryImpl` holds the playlist id and member item-ids per library. `refresh(libraryId)` fetches the playlist and updates the snapshot; mutations are optimistic and revert on failure. Both `LibraryItemsViewModel.init` and `LibraryItemDetailViewModel` call `refresh` to keep the cache warm. Tradeoff: cache is empty after process death until the first refresh — acceptable because the only consumers (tab + detail screen) call `refresh` before they read.

The list is surfaced as a new "To Read" tab in the library screen, positioned between Home and Series. The tab content is a grid of `LibraryItem`s, derived by joining the in-memory item-ids with the existing `observeAllBooks` Room cache.

## Alternatives considered

**Per-user-named Collection** (e.g. `To Read — {username}`). Rejected: clutters the Collections tab in the ABS web UI, exposes usernames to other users browsing collections, and still rides on library-global storage so a user with permission could mutate another user's list out-of-band.

**Local-only Room storage keyed by (serverId, userId, itemId).** Rejected for this fix: invisible to the ABS web UI (loses the cross-client visibility property ADR 0018 valued), doesn't sync across Riffle installs without new sync infrastructure. Still a reasonable option if/when a unified sync layer lands.

**Adding a Playlist Room cache symmetric to Collections.** Rejected for this fix: requires a new entity, DAO, migration, and refresh plumbing. Disproportionate scope for an asap correctness fix. In-memory caching covers the current call sites; Room persistence is a follow-up if To Read needs to survive process death (e.g. for an offline-first widget).

## Consequences

- **Per-account isolation.** Each ABS account on the same server now has its own To Read list. Cross-account leak fixed.
- **Visible in the ABS web UI under Playlists.** The web UI renders playlists with audio-playback affordances even for ebook-only items. This is cosmetic and accepted as the cost of using the only per-user-scoped ABS mechanism that holds arbitrary book lists.
- **Legacy "To Read" Collections on existing servers are abandoned.** Users with pre-ADR-0019 history have a stranded `To Read` Collection visible in the ABS web UI's Collections tab. The app does not delete it, migrate from it, or read from it. Users may delete it manually.
- **One network round-trip per library entry and per detail-screen open.** Both ViewModels call `refresh` before reading; the cache is shared across the To Read tab and the detail screen for the lifetime of the process.
- **To Read tab is a new top-level surface in the library view.** Lives between Home and Series. Empty state reads "Nothing in To Read".
- **Rename behaviour matches ADR 0018** — a user-side rename of `To Read` creates a duplicate on next toggle.
- **Empty playlists are auto-deleted by ABS.** Removing the last item from the playlist makes ABS delete the playlist itself. The repository handles this transparently by clearing its cached `playlistId` whenever a successful remove empties the cache; the next add creates a fresh playlist. No empty tile is left in the user's Playlists tab.
- **Offline taps still fail loudly.** Same behaviour as ADR 0018 — no queueing, no silent retry.
```

- [ ] **Step 2: Flip ADR 0018 status**

Edit the top of `docs/adr/0018-to-read-as-named-collection.md`. Change:

```
**Status:** Accepted
```

to:

```
**Status:** Superseded by [ADR 0019](0019-to-read-as-playlist.md). The "library-scoped Collection" reasoning held, but the deeper premise — that ABS Collections are user-defined groupings — turned out to be wrong: Collections are library-global and shared across all users with library access. See ADR 0019 for the playlist-backed implementation.
```

- [ ] **Step 3: Update `CONTEXT.md`**

Open `CONTEXT.md`, find the "To Read" entry (grep for `To Read`), and update it to reference Playlists + ADR 0019 instead of Collections + ADR 0018. Keep the same general structure; just swap the mechanism and the ADR number.

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0019-to-read-as-playlist.md docs/adr/0018-to-read-as-named-collection.md CONTEXT.md
git commit -m "docs: ADR 0019 — To Read backed by Playlist (supersedes 0018)"
```

---

## Task 9: End-to-end verification

- [ ] **Step 1: Full build + unit tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Harness tests**

Run: `make harness-test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke test against the real ABS server**

Install on the dev device. Verify:

1. With user A logged in, open a book, tap To Read → icon flips on. Navigate back to the library → the **To Read tab** (between Home and Series) shows the book.
2. Log out, log in as user B, open the same library → the To Read tab is empty (this is the bug we're fixing — proves per-user isolation works).
3. As user B, tap To Read on a different book → flips on, appears in B's To Read tab, A's tab is unaffected.
4. Mark a book Read on user A → it's removed from A's To Read tab but not B's.
5. Open the ABS web UI under user A → the `To Read` playlist appears under Playlists.
6. Tap an item in the To Read tab → detail screen opens with the bookmark on.

If any of (1)–(6) fail, the fix is incomplete — diagnose before declaring done.

- [ ] **Step 4: Final commit / PR prep**

No code change at this step. Confirm the working tree is clean. The PR should reference ADR 0019 and note that pre-existing "To Read" ABS Collections are left untouched on the server.

---

## Self-review

- Spec coverage: collection → playlist swap (Tasks 1–4), ViewModel wiring (5), UI tab (6), harness (7), docs (8), verification (9). ✓
- Type consistency: `TO_READ_PLAYLIST_NAME`, `ToReadSnapshot`, `observeToReadItemIds`, `refresh(libraryId)` consistent across tasks. ✓
- Tab ordering consistency: Home (0), To Read (1), Series (2), Collections (3), All Books (4) — applied in `LibraryTabBar` and the `when (selectedTab)` block in Task 6. ✓
- Behaviour change documented in ADR: `isInToRead` no longer hits the network on its own; callers must `refresh` first. Task 5 wires both ViewModels accordingly. ✓
- Endpoint shapes verified against live ABS server on 2026-05-30: `/item` singular for add/remove (docs incorrectly say plural). Recorded in "ABS endpoint reference".
- ABS auto-deletes empty playlists. Task 4's `removeFromToRead` clears the cached `playlistId` when emptying; covered by the `clears cached playlistId when last item is removed` test.
