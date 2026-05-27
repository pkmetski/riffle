# To Read Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-book "To Read" toggle to the Library Item Detail Screen, backed by a name-matched ABS Collection per Library, with asymmetric Read↔To Read coupling.

**Architecture:** New `ToReadRepository` (find-or-create the "To Read" Collection by name in the active library; add/remove a book via three new ABS Collection write endpoints on `AbsApiClient`). The detail-screen ViewModel adds a `toReadState: Boolean` to its UI state, performs an optimistic flip on toggle, awaits the network result, and reverts + emits a snackbar on failure. `markAsRead()` is extended to also remove from To Read, enforcing the invariant "Read books are never in To Read." The UI adds a third 40dp circular icon button (bookmark outline / bookmark filled) to the action row.

**Tech Stack:** Kotlin + Coroutines + Hilt, OkHttp + kotlinx.serialization (existing `AbsApiClient` pattern), Jetpack Compose Material3, MockWebServer + JUnit 4 for tests.

**Source of truth for design decisions:** [ADR 0018](../../adr/0018-to-read-as-named-collection.md) and the `To Read` and `Collection` entries in `CONTEXT.md`. If a step here contradicts ADR 0018, the ADR wins.

---

## File Map

| File | Change |
|------|--------|
| `core/network/src/main/kotlin/com/riffle/core/network/AbsLibraryApi.kt` | Add 3 suspend methods: `createCollection`, `addBookToCollection`, `removeBookFromCollection` |
| `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt` | Implement the 3 new methods, OkHttp + Bearer token |
| `core/network/src/main/kotlin/com/riffle/core/network/model/AbsCollectionWriteRequests.kt` | **New** — request DTOs (`AbsCreateCollectionRequest`, `AbsCollectionBookRequest`) and response DTO for create (`AbsCollectionDto` returned) |
| `core/network/src/main/kotlin/com/riffle/core/network/NetworkCollectionWriteResult.kt` | **New** — sealed `NetworkCollectionWriteResult` (Success(NetworkCollection?) / NetworkError) — same shape as the existing `NetworkCollectionResult` for consistency |
| `core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientCollectionsWriteTest.kt` | **New** — MockWebServer tests for the 3 endpoints |
| `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepository.kt` | **New** — interface |
| `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt` | **New** — implementation (find-or-create by name, add, remove) |
| `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt` | Bind `ToReadRepository` to `ToReadRepositoryImpl` |
| `core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt` | **New** — repository tests with fakes |
| `app/src/main/kotlin/com/riffle/app/feature/library/ToReadToggleButton.kt` | **New** — 40dp circular bookmark icon composable, mirrors `ReadToggleButton` |
| `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt` | Add `toReadState` field, `toggleToRead()`, and remove-from-To-Read coupling in `markAsRead()` |
| `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTest.kt` | Tests for optimistic flip, revert on failure, Read→remove coupling |
| `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt` | Insert `ToReadToggleButton` between mark-read and download; wire snackbar event flow |

The "To Read" string is hardcoded as `"To Read"` (case-sensitive) — this is a server-visible Collection name, not a translatable label.

The button's `contentDescription` is hardcoded inline (matches the existing `ReadToggleButton` pattern at `ReadToggleButton.kt:41`).

---

## Task 1: AbsLibraryApi interface + write request/response DTOs

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsLibraryApi.kt`
- Create: `core/network/src/main/kotlin/com/riffle/core/network/model/AbsCollectionWriteRequests.kt`
- Create: `core/network/src/main/kotlin/com/riffle/core/network/NetworkCollectionWriteResult.kt`

This task defines the contract only. No `AbsApiClient` implementation yet — `AbsApiClient` will stub `TODO()` so the module still compiles after this task.

- [ ] **Step 1: Create the result type**

Create `core/network/src/main/kotlin/com/riffle/core/network/NetworkCollectionWriteResult.kt`:

```kotlin
package com.riffle.core.network

/**
 * Result of a Collection write operation (create, add book, remove book).
 *
 * On success, `collection` is the resulting collection state (for create + add).
 * For remove, the server may or may not return the updated collection — callers should
 * not rely on its presence.
 */
sealed class NetworkCollectionWriteResult {
    data class Success(val collection: NetworkCollection?) : NetworkCollectionWriteResult()
    data class NetworkError(val cause: Throwable) : NetworkCollectionWriteResult()
}
```

- [ ] **Step 2: Create the request DTOs**

Create `core/network/src/main/kotlin/com/riffle/core/network/model/AbsCollectionWriteRequests.kt`:

```kotlin
package com.riffle.core.network.model

import kotlinx.serialization.Serializable

/** POST /api/collections body. */
@Serializable
internal data class AbsCreateCollectionRequest(
    val libraryId: String,
    val name: String,
    // No default: kotlinx serialization omits fields equal to their default unless
    // `encodeDefaults = true` is set, and the wire format requires `books` to always
    // be present. The only caller passes the list explicitly.
    val books: List<String>,
)

/** POST /api/collections/:id/book body. */
@Serializable
internal data class AbsCollectionBookRequest(
    val id: String,
)
```

- [ ] **Step 3: Extend the AbsLibraryApi interface**

In `core/network/src/main/kotlin/com/riffle/core/network/AbsLibraryApi.kt`, add three suspend methods alongside the existing `getCollections` declaration:

```kotlin
/**
 * POST /api/collections. Creates a Collection in [libraryId] named [name]. If [initialBookId]
 * is non-null it is included as the collection's first (and only) book. The ABS endpoint
 * accepts a list, but the only caller — `ToReadRepository` — adds one book at a time.
 */
suspend fun createCollection(
    baseUrl: String,
    libraryId: String,
    name: String,
    initialBookId: String?,
    token: String,
    insecureAllowed: Boolean,
): NetworkCollectionWriteResult

suspend fun addBookToCollection(
    baseUrl: String,
    collectionId: String,
    libraryItemId: String,
    token: String,
    insecureAllowed: Boolean,
): NetworkCollectionWriteResult

suspend fun removeBookFromCollection(
    baseUrl: String,
    collectionId: String,
    libraryItemId: String,
    token: String,
    insecureAllowed: Boolean,
): NetworkCollectionWriteResult
```

- [ ] **Step 4: Add stub implementations to AbsApiClient**

In `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`, add three `override` methods with `TODO("Implemented in Task 2/3/4")`. This keeps the module compilable.

```kotlin
override suspend fun createCollection(
    baseUrl: String,
    libraryId: String,
    name: String,
    initialBookId: String?,
    token: String,
    insecureAllowed: Boolean,
): NetworkCollectionWriteResult = TODO("Task 2")

override suspend fun addBookToCollection(
    baseUrl: String,
    collectionId: String,
    libraryItemId: String,
    token: String,
    insecureAllowed: Boolean,
): NetworkCollectionWriteResult = TODO("Task 3")

override suspend fun removeBookFromCollection(
    baseUrl: String,
    collectionId: String,
    libraryItemId: String,
    token: String,
    insecureAllowed: Boolean,
): NetworkCollectionWriteResult = TODO("Task 4")
```

- [ ] **Step 5: Build the network module**

Run: `./gradlew :core:network:assemble`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/NetworkCollectionWriteResult.kt \
        core/network/src/main/kotlin/com/riffle/core/network/model/AbsCollectionWriteRequests.kt \
        core/network/src/main/kotlin/com/riffle/core/network/AbsLibraryApi.kt \
        core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt
git commit -m "feat(network): declare Collection write API (create/add/remove)"
```

---

## Task 2: Implement `createCollection` on AbsApiClient

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`
- Create: `core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientCollectionsWriteTest.kt`

ABS endpoint: `POST /api/collections` with JSON body `{ libraryId, name, books: [libraryItemId] }`. Returns the created collection (the existing `AbsCollectionDto` shape from `AbsCollectionsResponse`).

- [ ] **Step 1: Write the failing test**

Create `core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientCollectionsWriteTest.kt`:

```kotlin
package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbsApiClientCollectionsWriteTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AbsApiClient

    @Before fun setUp() { server = MockWebServer(); server.start(); client = AbsApiClient(OkHttpClient()) }
    @After fun tearDown() { server.shutdown() }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun `createCollection posts libraryId name and book and returns parsed collection`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"id":"col-1","name":"To Read","libraryId":"lib-1","books":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"T","authorName":"A"},"ebookFile":null,"coverPath":null}}]}"""
                )
                .addHeader("Content-Type", "application/json")
        )

        val result = client.createCollection(baseUrl(), "lib-1", "To Read", "item-1", "tok", false)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/collections", recorded.path)
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"libraryId\":\"lib-1\""))
        assertTrue(body.contains("\"name\":\"To Read\""))
        assertTrue(body.contains("\"books\":[\"item-1\"]"))

        assertTrue(result is NetworkCollectionWriteResult.Success)
        val collection = (result as NetworkCollectionWriteResult.Success).collection
        assertEquals("col-1", collection?.id)
        assertEquals("To Read", collection?.name)
        assertEquals(1, collection?.items?.size)
    }

    @Test
    fun `createCollection without initial book sends empty books array`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"col-1","name":"To Read","libraryId":"lib-1","books":[]}"""
            ).addHeader("Content-Type", "application/json")
        )
        client.createCollection(baseUrl(), "lib-1", "To Read", null, "tok", false)
        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"books\":[]"))
    }

    @Test
    fun `createCollection returns NetworkError on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = client.createCollection(baseUrl(), "lib-1", "To Read", null, "tok", false)
        assertTrue(result is NetworkCollectionWriteResult.NetworkError)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:network:test --tests com.riffle.core.network.AbsApiClientCollectionsWriteTest`
Expected: tests fail with `NotImplementedError` ("Task 2").

- [ ] **Step 3: Implement `createCollection`**

In `AbsApiClient.kt`, replace the stub. Mirror the existing `syncEbookProgress` pattern (OkHttp POST, JSON body via `toRequestBody(jsonMediaType)`, parse via `json.decodeFromString<AbsCollectionsResponse.AbsCollectionDto>`):

```kotlin
override suspend fun createCollection(
    baseUrl: String,
    libraryId: String,
    name: String,
    initialBookId: String?,
    token: String,
    insecureAllowed: Boolean,
): NetworkCollectionWriteResult = withContext(Dispatchers.IO) {
    val httpClient = if (insecureAllowed) this@AbsApiClient.httpClient.trustAllCerts() else this@AbsApiClient.httpClient
    val payload = AbsCreateCollectionRequest(
        libraryId = libraryId,
        name = name,
        books = listOfNotNull(initialBookId),
    )
    val body = json.encodeToString(AbsCreateCollectionRequest.serializer(), payload).toRequestBody(jsonMediaType)
    val request = Request.Builder()
        .url("$baseUrl/api/collections")
        .addHeader("Authorization", "Bearer $token")
        .post(body)
        .build()
    try {
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext NetworkCollectionWriteResult.NetworkError(IOException("HTTP ${response.code}"))
        val raw = response.body?.string().orEmpty()
        val dto = json.decodeFromString(AbsCollectionsResponse.AbsCollectionDto.serializer(), raw)
        NetworkCollectionWriteResult.Success(dto.toNetworkCollection())
    } catch (e: Exception) {
        NetworkCollectionWriteResult.NetworkError(e)
    }
}
```

You'll need a helper `AbsCollectionsResponse.AbsCollectionDto.toNetworkCollection()` extension. The existing `getCollections` code already maps `AbsCollectionDto → NetworkCollection`; extract that mapping into a private/internal extension function inside `AbsCollectionsResponse.kt` (or a sibling file) and reuse it from both `getCollections` and the new `createCollection`. Do **not** duplicate the mapping inline.

- [ ] **Step 4: Run the tests until they pass**

Run: `./gradlew :core:network:test --tests com.riffle.core.network.AbsApiClientCollectionsWriteTest`
Expected: 3 passing tests.

- [ ] **Step 5: Verify the existing collections test still passes**

Run: `./gradlew :core:network:test`
Expected: all network tests pass (the extracted mapping helper should not have changed behaviour).

- [ ] **Step 6: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt \
        core/network/src/main/kotlin/com/riffle/core/network/model/AbsCollectionsResponse.kt \
        core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientCollectionsWriteTest.kt
git commit -m "feat(network): implement createCollection endpoint"
```

---

## Task 3: Implement `addBookToCollection` on AbsApiClient

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`
- Modify: `core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientCollectionsWriteTest.kt`

ABS endpoint: `POST /api/collections/:id/book` with body `{ id: "<libraryItemId>" }`. Returns the updated collection.

- [ ] **Step 1: Add failing tests**

Append to `AbsApiClientCollectionsWriteTest.kt`:

```kotlin
@Test
fun `addBookToCollection posts libraryItemId to collection book endpoint`() = runTest {
    server.enqueue(
        MockResponse().setResponseCode(200).setBody(
            """{"id":"col-1","name":"To Read","libraryId":"lib-1","books":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"T","authorName":"A"},"ebookFile":null,"coverPath":null}}]}"""
        ).addHeader("Content-Type", "application/json")
    )
    val result = client.addBookToCollection(baseUrl(), "col-1", "item-1", "tok", false)
    val recorded = server.takeRequest()
    assertEquals("POST", recorded.method)
    assertEquals("/api/collections/col-1/book", recorded.path)
    assertEquals("Bearer tok", recorded.getHeader("Authorization"))
    assertTrue(recorded.body.readUtf8().contains("\"id\":\"item-1\""))
    assertTrue(result is NetworkCollectionWriteResult.Success)
    assertEquals("col-1", (result as NetworkCollectionWriteResult.Success).collection?.id)
}

@Test
fun `addBookToCollection returns NetworkError on 404`() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    val result = client.addBookToCollection(baseUrl(), "col-1", "item-1", "tok", false)
    assertTrue(result is NetworkCollectionWriteResult.NetworkError)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:network:test --tests com.riffle.core.network.AbsApiClientCollectionsWriteTest`
Expected: 2 new tests fail with `NotImplementedError`.

- [ ] **Step 3: Implement `addBookToCollection`**

In `AbsApiClient.kt`, replace the stub:

```kotlin
override suspend fun addBookToCollection(
    baseUrl: String,
    collectionId: String,
    libraryItemId: String,
    token: String,
    insecureAllowed: Boolean,
): NetworkCollectionWriteResult = withContext(Dispatchers.IO) {
    val httpClient = if (insecureAllowed) this@AbsApiClient.httpClient.trustAllCerts() else this@AbsApiClient.httpClient
    val body = json.encodeToString(AbsCollectionBookRequest.serializer(), AbsCollectionBookRequest(libraryItemId))
        .toRequestBody(jsonMediaType)
    val request = Request.Builder()
        .url("$baseUrl/api/collections/$collectionId/book")
        .addHeader("Authorization", "Bearer $token")
        .post(body)
        .build()
    try {
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext NetworkCollectionWriteResult.NetworkError(IOException("HTTP ${response.code}"))
        val raw = response.body?.string().orEmpty()
        val dto = json.decodeFromString(AbsCollectionsResponse.AbsCollectionDto.serializer(), raw)
        NetworkCollectionWriteResult.Success(dto.toNetworkCollection())
    } catch (e: Exception) {
        NetworkCollectionWriteResult.NetworkError(e)
    }
}
```

- [ ] **Step 4: Run the tests until they pass**

Run: `./gradlew :core:network:test --tests com.riffle.core.network.AbsApiClientCollectionsWriteTest`
Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt \
        core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientCollectionsWriteTest.kt
git commit -m "feat(network): implement addBookToCollection endpoint"
```

---

## Task 4: Implement `removeBookFromCollection` on AbsApiClient

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`
- Modify: `core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientCollectionsWriteTest.kt`

ABS endpoint: `DELETE /api/collections/:id/book/:libraryItemId`. Returns the updated collection (or empty body — we tolerate both).

- [ ] **Step 1: Add failing tests**

Append to `AbsApiClientCollectionsWriteTest.kt`:

```kotlin
@Test
fun `removeBookFromCollection deletes the libraryItem from the collection`() = runTest {
    server.enqueue(
        MockResponse().setResponseCode(200).setBody(
            """{"id":"col-1","name":"To Read","libraryId":"lib-1","books":[]}"""
        ).addHeader("Content-Type", "application/json")
    )
    val result = client.removeBookFromCollection(baseUrl(), "col-1", "item-1", "tok", false)
    val recorded = server.takeRequest()
    assertEquals("DELETE", recorded.method)
    assertEquals("/api/collections/col-1/book/item-1", recorded.path)
    assertEquals("Bearer tok", recorded.getHeader("Authorization"))
    assertTrue(result is NetworkCollectionWriteResult.Success)
}

@Test
fun `removeBookFromCollection tolerates empty body on success`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(""))
    val result = client.removeBookFromCollection(baseUrl(), "col-1", "item-1", "tok", false)
    assertTrue(result is NetworkCollectionWriteResult.Success)
    assertEquals(null, (result as NetworkCollectionWriteResult.Success).collection)
}

@Test
fun `removeBookFromCollection returns NetworkError on 404`() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    val result = client.removeBookFromCollection(baseUrl(), "col-1", "item-1", "tok", false)
    assertTrue(result is NetworkCollectionWriteResult.NetworkError)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:network:test --tests com.riffle.core.network.AbsApiClientCollectionsWriteTest`
Expected: 3 new tests fail with `NotImplementedError`.

- [ ] **Step 3: Implement `removeBookFromCollection`**

In `AbsApiClient.kt`:

```kotlin
override suspend fun removeBookFromCollection(
    baseUrl: String,
    collectionId: String,
    libraryItemId: String,
    token: String,
    insecureAllowed: Boolean,
): NetworkCollectionWriteResult = withContext(Dispatchers.IO) {
    val httpClient = if (insecureAllowed) this@AbsApiClient.httpClient.trustAllCerts() else this@AbsApiClient.httpClient
    val request = Request.Builder()
        .url("$baseUrl/api/collections/$collectionId/book/$libraryItemId")
        .addHeader("Authorization", "Bearer $token")
        .delete()
        .build()
    try {
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext NetworkCollectionWriteResult.NetworkError(IOException("HTTP ${response.code}"))
        val raw = response.body?.string().orEmpty()
        val collection = if (raw.isBlank()) null else runCatching {
            json.decodeFromString(AbsCollectionsResponse.AbsCollectionDto.serializer(), raw).toNetworkCollection()
        }.getOrNull()
        NetworkCollectionWriteResult.Success(collection)
    } catch (e: Exception) {
        NetworkCollectionWriteResult.NetworkError(e)
    }
}
```

- [ ] **Step 4: Run the tests until they pass**

Run: `./gradlew :core:network:test --tests com.riffle.core.network.AbsApiClientCollectionsWriteTest`
Expected: all 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt \
        core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientCollectionsWriteTest.kt
git commit -m "feat(network): implement removeBookFromCollection endpoint"
```

---

## Task 5: ToReadRepository interface and `isInToRead` query

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepository.kt`
- Create: `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`
- Create: `core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt`

This task introduces the repository and its first method: `isInToRead`. The repository resolves the active server + token (same pattern as `LibraryRepositoryImpl`), GETs the collections for the given library, and returns whether the named "To Read" collection contains the item.

The reserved collection name is exposed as a top-level constant in `ToReadRepository.kt`:

```kotlin
const val TO_READ_COLLECTION_NAME = "To Read"
```

- [ ] **Step 1: Write the failing test**

Create `core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt`. Mirror the fake-pattern from `LibraryRepositoryTest.kt` (FakeServerRepository, FakeTokenStorage):

```kotlin
package com.riffle.core.data

import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerUrl
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollectionWriteResult
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkCollectionResult
import com.riffle.core.network.NetworkLibraryItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToReadRepositoryTest {

    private val activeServer = Server(
        id = "s1",
        url = ServerUrl("http://abs.local"),
        displayName = "ABS",
        isActive = true,
        insecureConnectionAllowed = false,
        username = "u",
    )

    private fun makeRepo(api: AbsLibraryApi): ToReadRepositoryImpl =
        ToReadRepositoryImpl(
            api = api,
            serverRepository = FakeServerRepository(activeServer),
            tokenStorage = FakeTokenStorage(mapOf("s1" to "tok")),
        )

    @Test
    fun `isInToRead returns true when book is in the To Read collection`() = runTest {
        val api = FakeAbsApi(
            collectionsByLibrary = mapOf(
                "lib-1" to listOf(
                    NetworkCollection("col-A", "lib-1", "To Read", listOf(stubItem("item-1"))),
                ),
            ),
        )
        assertTrue(makeRepo(api).isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `isInToRead returns false when To Read collection has no such book`() = runTest {
        val api = FakeAbsApi(
            collectionsByLibrary = mapOf(
                "lib-1" to listOf(
                    NetworkCollection("col-A", "lib-1", "To Read", listOf(stubItem("item-9"))),
                ),
            ),
        )
        assertFalse(makeRepo(api).isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `isInToRead returns false when no To Read collection exists`() = runTest {
        val api = FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList()))
        assertFalse(makeRepo(api).isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `isInToRead returns false when no active server`() = runTest {
        val repo = ToReadRepositoryImpl(
            api = FakeAbsApi(),
            serverRepository = FakeServerRepository(activeServer = null),
            tokenStorage = FakeTokenStorage(emptyMap()),
        )
        assertFalse(repo.isInToRead("item-1", "lib-1"))
    }

    private fun stubItem(id: String) = NetworkLibraryItem(
        id = id,
        libraryId = "lib-1",
        title = "T",
        author = "A",
        coverUrl = null,
        ebookFormat = com.riffle.core.network.NetworkEbookFormat.Epub,
        ebookFileIno = null,
    )
}
```

Implement the test-only fakes in the same file (or extract to a shared `FakeAbsApi.kt` if you find duplication elsewhere). Minimal `FakeAbsApi`:

```kotlin
private class FakeAbsApi(
    val collectionsByLibrary: Map<String, List<NetworkCollection>> = emptyMap(),
) : AbsLibraryApi {
    val createCalls = mutableListOf<Triple<String, String, String?>>() // libraryId, name, initialBookId
    val addCalls = mutableListOf<Pair<String, String>>() // collectionId, libraryItemId
    val removeCalls = mutableListOf<Pair<String, String>>()

    override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
        NetworkCollectionResult.Success(collectionsByLibrary[libraryId].orEmpty())

    override suspend fun createCollection(baseUrl: String, libraryId: String, name: String, initialBookId: String?, token: String, insecureAllowed: Boolean): NetworkCollectionWriteResult {
        createCalls += Triple(libraryId, name, initialBookId)
        val newCol = NetworkCollection("col-new", libraryId, name, emptyList())
        return NetworkCollectionWriteResult.Success(newCol)
    }

    override suspend fun addBookToCollection(baseUrl: String, collectionId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkCollectionWriteResult {
        addCalls += collectionId to libraryItemId
        return NetworkCollectionWriteResult.Success(null)
    }

    override suspend fun removeBookFromCollection(baseUrl: String, collectionId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkCollectionWriteResult {
        removeCalls += collectionId to libraryItemId
        return NetworkCollectionWriteResult.Success(null)
    }

    // Other AbsLibraryApi methods: throw NotImplementedError(); these tests don't exercise them.
    // Implement no-op stubs for every other interface method — DO NOT delete methods from the interface.
}
```

If `AbsLibraryApi` has many other methods, write `override fun ...() = TODO("not used in ToReadRepositoryTest")` for each so the fake compiles. Do not omit any interface method.

- [ ] **Step 2: Define the interface**

Create `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepository.kt`:

```kotlin
package com.riffle.core.data

const val TO_READ_COLLECTION_NAME = "To Read"

/**
 * Manages the per-Library "To Read" Collection on the active ABS server.
 *
 * The list is backed by a normal ABS Collection named [TO_READ_COLLECTION_NAME], looked
 * up by name and find-or-created on first use. See ADR 0018.
 */
interface ToReadRepository {
    /** Returns true if [libraryItemId] is currently in the "To Read" collection of [libraryId]. */
    suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean

    /**
     * Adds [libraryItemId] to the "To Read" collection of [libraryId], creating the
     * collection if it does not yet exist. Returns true on success.
     */
    suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean

    /**
     * Removes [libraryItemId] from the "To Read" collection of [libraryId]. Returns true
     * on success or if the collection / membership did not exist (a no-op success).
     */
    suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean
}
```

- [ ] **Step 3: Implement `isInToRead`**

Create `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt`:

```kotlin
package com.riffle.core.data

import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkCollectionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToReadRepositoryImpl @Inject constructor(
    private val api: AbsLibraryApi,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : ToReadRepository {

    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean {
        val collection = findToReadCollection(libraryId) ?: return false
        return collection.items.any { it.id == libraryItemId }
    }

    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean =
        TODO("Task 6")

    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean =
        TODO("Task 7")

    private suspend fun findToReadCollection(libraryId: String): NetworkCollection? {
        val server = serverRepository.getActive() ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        val result = api.getCollections(server.url.value, libraryId, token, server.insecureConnectionAllowed)
        if (result !is NetworkCollectionResult.Success) return null
        return result.collections.firstOrNull { it.name == TO_READ_COLLECTION_NAME }
    }
}
```

- [ ] **Step 4: Bind in DataModule**

In `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`, add the binding next to other `@Binds @Singleton` declarations:

```kotlin
@Binds
@Singleton
abstract fun bindToReadRepository(impl: ToReadRepositoryImpl): ToReadRepository
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core:data:test --tests com.riffle.core.data.ToReadRepositoryTest`
Expected: 4 `isInToRead` tests pass.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/ToReadRepository.kt \
        core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt \
        core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt \
        core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt
git commit -m "feat(data): add ToReadRepository with isInToRead query"
```

---

## Task 6: `addToToRead` with find-or-create

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt`

If the "To Read" collection exists: `addBookToCollection`. If it does not: `createCollection` with the book as the initial member (one network call instead of two).

- [ ] **Step 1: Write the failing tests**

Append to `ToReadRepositoryTest.kt`:

```kotlin
@Test
fun `addToToRead creates the collection with the book when no To Read exists`() = runTest {
    val api = FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList()))
    val ok = makeRepo(api).addToToRead("item-1", "lib-1")
    assertTrue(ok)
    assertEquals(listOf(Triple("lib-1", "To Read", "item-1")), api.createCalls)
    assertTrue(api.addCalls.isEmpty())
}

@Test
fun `addToToRead adds to existing To Read collection`() = runTest {
    val api = FakeAbsApi(
        collectionsByLibrary = mapOf(
            "lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", emptyList())),
        ),
    )
    val ok = makeRepo(api).addToToRead("item-1", "lib-1")
    assertTrue(ok)
    assertTrue(api.createCalls.isEmpty())
    assertEquals(listOf("col-A" to "item-1"), api.addCalls)
}

@Test
fun `addToToRead returns false when no active server`() = runTest {
    val repo = ToReadRepositoryImpl(
        api = FakeAbsApi(),
        serverRepository = FakeServerRepository(activeServer = null),
        tokenStorage = FakeTokenStorage(emptyMap()),
    )
    assertFalse(repo.addToToRead("item-1", "lib-1"))
}

@Test
fun `addToToRead returns false when create fails`() = runTest {
    val api = object : FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList())) {
        override suspend fun createCollection(baseUrl: String, libraryId: String, name: String, initialBookId: String?, token: String, insecureAllowed: Boolean): NetworkCollectionWriteResult =
            NetworkCollectionWriteResult.NetworkError(java.io.IOException("HTTP 500"))
    }
    assertFalse(makeRepo(api).addToToRead("item-1", "lib-1"))
}

@Test
fun `addToToRead returns false when add fails`() = runTest {
    val api = object : FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", emptyList())))) {
        override suspend fun addBookToCollection(baseUrl: String, collectionId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkCollectionWriteResult =
            NetworkCollectionWriteResult.NetworkError(java.io.IOException("HTTP 500"))
    }
    assertFalse(makeRepo(api).addToToRead("item-1", "lib-1"))
}
```

Make `FakeAbsApi` `open` so the subclasses above compile, and mark its three relevant methods `open`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:data:test --tests com.riffle.core.data.ToReadRepositoryTest`
Expected: 5 new tests fail with `NotImplementedError`.

- [ ] **Step 3: Implement `addToToRead`**

In `ToReadRepositoryImpl.kt`, replace the stub:

```kotlin
override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
    val server = serverRepository.getActive() ?: return false
    val token = tokenStorage.getToken(server.id) ?: return false
    val baseUrl = server.url.value
    val existing = findToReadCollection(libraryId)
    val result = if (existing == null) {
        api.createCollection(baseUrl, libraryId, TO_READ_COLLECTION_NAME, libraryItemId, token, server.insecureConnectionAllowed)
    } else {
        api.addBookToCollection(baseUrl, existing.id, libraryItemId, token, server.insecureConnectionAllowed)
    }
    return result is NetworkCollectionWriteResult.Success
}
```

- [ ] **Step 4: Run the tests until they pass**

Run: `./gradlew :core:data:test --tests com.riffle.core.data.ToReadRepositoryTest`
Expected: all 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt
git commit -m "feat(data): implement addToToRead with find-or-create"
```

---

## Task 7: `removeFromToRead` no-op when missing

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `ToReadRepositoryTest.kt`:

```kotlin
@Test
fun `removeFromToRead calls DELETE when collection exists`() = runTest {
    val api = FakeAbsApi(
        collectionsByLibrary = mapOf(
            "lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", listOf(stubItem("item-1")))),
        ),
    )
    val ok = makeRepo(api).removeFromToRead("item-1", "lib-1")
    assertTrue(ok)
    assertEquals(listOf("col-A" to "item-1"), api.removeCalls)
}

@Test
fun `removeFromToRead returns true and makes no call when no To Read collection`() = runTest {
    val api = FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList()))
    val ok = makeRepo(api).removeFromToRead("item-1", "lib-1")
    assertTrue(ok)
    assertTrue(api.removeCalls.isEmpty())
}

@Test
fun `removeFromToRead returns false when remove fails`() = runTest {
    val api = object : FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", listOf(stubItem("item-1")))))) {
        override suspend fun removeBookFromCollection(baseUrl: String, collectionId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkCollectionWriteResult =
            NetworkCollectionWriteResult.NetworkError(java.io.IOException("HTTP 500"))
    }
    assertFalse(makeRepo(api).removeFromToRead("item-1", "lib-1"))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:data:test --tests com.riffle.core.data.ToReadRepositoryTest`
Expected: 3 new tests fail with `NotImplementedError`.

- [ ] **Step 3: Implement `removeFromToRead`**

In `ToReadRepositoryImpl.kt`:

```kotlin
override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
    val server = serverRepository.getActive() ?: return false
    val token = tokenStorage.getToken(server.id) ?: return false
    val existing = findToReadCollection(libraryId) ?: return true // no-op success
    val result = api.removeBookFromCollection(server.url.value, existing.id, libraryItemId, token, server.insecureConnectionAllowed)
    return result is NetworkCollectionWriteResult.Success
}
```

- [ ] **Step 4: Run the tests until they pass**

Run: `./gradlew :core:data:test --tests com.riffle.core.data.ToReadRepositoryTest`
Expected: all 12 tests pass.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/ToReadRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/ToReadRepositoryTest.kt
git commit -m "feat(data): implement removeFromToRead with missing-collection no-op"
```

---

## Task 8: ToReadToggleButton composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/library/ToReadToggleButton.kt`

Mirrors `ReadToggleButton.kt:21-46` exactly — same 40dp circular shape, same active/inactive states, same colour scheme — but with `Icons.Filled.Bookmark` / `Icons.Outlined.BookmarkBorder` and different content descriptions.

- [ ] **Step 1: Create the composable**

```kotlin
package com.riffle.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box

@Composable
fun ToReadToggleButton(
    isInToRead: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (isInToRead) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            .clickable(onClick = if (isInToRead) onRemove else onAdd),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isInToRead) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = if (isInToRead) "Remove from To Read" else "Add to To Read",
            tint = if (isInToRead) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

If the `material-icons-extended` artifact is not on the `app` module's classpath, `Icons.Outlined.BookmarkBorder` will not resolve. In that case, use `Icons.Filled.BookmarkBorder` (Material core icons — `BookmarkBorder` is also in the filled set as a hollow variant) and confirm it renders as an outline. Check the existing icon imports in `LibraryItemDetailScreen.kt` and `ReadToggleButton.kt` to see which artifact is available.

- [ ] **Step 2: Build the app module**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/ToReadToggleButton.kt
git commit -m "feat(library): add ToReadToggleButton composable"
```

---

## Task 9: ViewModel — toReadState + optimistic toggleToRead with revert

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt`
- Modify: `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTest.kt`

Behaviour:
1. On `init` (or whenever the screen loads the item), the VM asks the repository whether the item is in To Read and stores it in `LibraryItemDetailUiState.Ready.isInToRead: Boolean`.
2. `toggleToRead()` flips the UI value **immediately** (optimistic), launches the repository call, and on failure flips the value back and emits a snackbar event.
3. Snackbar events are exposed as a `SharedFlow<String>` (or a `Channel` consumed once on the screen). Follow whatever event-emission pattern the surrounding ViewModel already uses; if none, add `private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)` and expose `val snackbarEvents = _snackbarEvents.asSharedFlow()`.

- [ ] **Step 1: Extend `LibraryItemDetailUiState.Ready` with `isInToRead`**

In `LibraryItemDetailViewModel.kt`, add the field:

```kotlin
sealed class LibraryItemDetailUiState {
    object Loading : LibraryItemDetailUiState()
    data class Ready(
        val item: LibraryItem,
        val isInToRead: Boolean = false,
        // ... existing fields preserved
    ) : LibraryItemDetailUiState()
    data class Error(...) : LibraryItemDetailUiState()
}
```

(Match the exact existing structure — do not remove existing fields. The `isInToRead = false` default avoids breaking call sites that haven't been updated yet.)

- [ ] **Step 2: Inject ToReadRepository and populate `isInToRead` on load**

In the ViewModel constructor, add `private val toReadRepository: ToReadRepository`. In whichever code path constructs the `Ready` state from the loaded `LibraryItem`, populate `isInToRead`:

```kotlin
val isInToRead = toReadRepository.isInToRead(item.id, item.libraryId)
_uiState.value = LibraryItemDetailUiState.Ready(item = item, isInToRead = isInToRead, /* ... */)
```

If the existing load path is a Flow-based collector, call `isInToRead` once per emission. (Cheap GET; acceptable until a future caching layer.)

- [ ] **Step 3: Write failing tests for toggleToRead**

In `LibraryItemDetailViewModelTest.kt`, add a `FakeToReadRepository` and tests. Replace any existing `makeVm` helper to accept the new dependency:

```kotlin
private class FakeToReadRepository(
    initial: Set<String> = emptySet(),
    val addResult: Boolean = true,
    val removeResult: Boolean = true,
) : ToReadRepository {
    val state = mutableSetOf<String>().also { it += initial }
    val addCalls = mutableListOf<Pair<String, String>>()
    val removeCalls = mutableListOf<Pair<String, String>>()

    override suspend fun isInToRead(libraryItemId: String, libraryId: String) = libraryItemId in state
    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
        addCalls += libraryItemId to libraryId
        if (addResult) state += libraryItemId
        return addResult
    }
    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
        removeCalls += libraryItemId to libraryId
        if (removeResult) state -= libraryItemId
        return removeResult
    }
}

@Test
fun `toggleToRead optimistically flips state and persists on success`() = runTest {
    val toRead = FakeToReadRepository()
    val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
    backgroundScope.launch { vm.uiState.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()
    assertFalse((vm.uiState.value as Ready).isInToRead)

    vm.toggleToRead()
    // After the optimistic flip, before coroutine resolves:
    assertTrue((vm.uiState.value as Ready).isInToRead)

    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue((vm.uiState.value as Ready).isInToRead)
    assertEquals(listOf(knownItem.id to knownItem.libraryId), toRead.addCalls)
}

@Test
fun `toggleToRead reverts state and emits snackbar on failure`() = runTest {
    val toRead = FakeToReadRepository(addResult = false)
    val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
    val snackbarMessages = mutableListOf<String>()
    backgroundScope.launch { vm.uiState.collect {} }
    backgroundScope.launch { vm.snackbarEvents.collect { snackbarMessages += it } }
    testDispatcher.scheduler.advanceUntilIdle()

    vm.toggleToRead()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse((vm.uiState.value as Ready).isInToRead)
    assertEquals(1, snackbarMessages.size)
    assertTrue(snackbarMessages.single().contains("To Read", ignoreCase = true))
}

@Test
fun `toggleToRead removes book when already in To Read`() = runTest {
    val toRead = FakeToReadRepository(initial = setOf(knownItem.id))
    val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
    backgroundScope.launch { vm.uiState.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue((vm.uiState.value as Ready).isInToRead)

    vm.toggleToRead()
    assertFalse((vm.uiState.value as Ready).isInToRead) // optimistic
    testDispatcher.scheduler.advanceUntilIdle()
    assertFalse((vm.uiState.value as Ready).isInToRead)
    assertEquals(listOf(knownItem.id to knownItem.libraryId), toRead.removeCalls)
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.riffle.app.feature.library.LibraryItemDetailViewModelTest`
Expected: 3 new tests fail (compilation error first — `toggleToRead` and `snackbarEvents` don't exist yet).

- [ ] **Step 5: Implement `toggleToRead` and `snackbarEvents`**

In `LibraryItemDetailViewModel.kt`:

```kotlin
private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

fun toggleToRead() {
    val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
    val wasInToRead = current.isInToRead
    // Optimistic flip
    _uiState.value = current.copy(isInToRead = !wasInToRead)
    viewModelScope.launch {
        val itemId = current.item.id
        val libraryId = current.item.libraryId
        val ok = if (wasInToRead) {
            toReadRepository.removeFromToRead(itemId, libraryId)
        } else {
            toReadRepository.addToToRead(itemId, libraryId)
        }
        if (!ok) {
            // Revert + snackbar
            val now = _uiState.value as? LibraryItemDetailUiState.Ready ?: return@launch
            _uiState.value = now.copy(isInToRead = wasInToRead)
            _snackbarEvents.tryEmit(
                if (wasInToRead) "Couldn't remove from To Read" else "Couldn't add to To Read"
            )
        }
    }
}
```

- [ ] **Step 6: Run the tests until they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.riffle.app.feature.library.LibraryItemDetailViewModelTest`
Expected: all VM tests pass (including the existing ones).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt \
        app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTest.kt
git commit -m "feat(library): toggleToRead with optimistic flip and revert on failure"
```

---

## Task 10: Read→true removes from To Read (asymmetric coupling)

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt`
- Modify: `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTest.kt`

Per ADR 0018: any transition to Read removes the book from To Read. The reverse coupling is not enforced. Today the only path that flips Read to true is `markAsRead()`. Future auto-finish paths must call the same removal logic — leave a `// invariant: ADR 0018 — Read books are never in To Read` comment on the removal call so future authors find it.

- [ ] **Step 1: Write the failing tests**

Append to `LibraryItemDetailViewModelTest.kt`:

```kotlin
@Test
fun `markAsRead also removes the book from To Read`() = runTest {
    val toRead = FakeToReadRepository(initial = setOf(knownItem.id))
    val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
    backgroundScope.launch { vm.uiState.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    vm.markAsRead()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(knownItem.id to knownItem.libraryId), toRead.removeCalls)
    assertFalse((vm.uiState.value as Ready).isInToRead)
}

@Test
fun `markAsUnread does not touch To Read`() = runTest {
    val toRead = FakeToReadRepository(initial = setOf(knownItem.id))
    val vm = makeVm(repo = fakeRepo(knownItem.copy(readingProgress = 1.0f)), toReadRepo = toRead)
    backgroundScope.launch { vm.uiState.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    vm.markAsUnread()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(toRead.removeCalls.isEmpty())
    assertTrue((vm.uiState.value as Ready).isInToRead)
}

@Test
fun `toggleToRead on a Read book does not clear the Read flag`() = runTest {
    val readItem = knownItem.copy(readingProgress = 1.0f)
    val toRead = FakeToReadRepository()
    val vm = makeVm(repo = fakeRepo(readItem), toReadRepo = toRead)
    backgroundScope.launch { vm.uiState.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    vm.toggleToRead()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1.0f, (vm.uiState.value as Ready).item.readingProgress, 0.0001f)
    assertTrue((vm.uiState.value as Ready).isInToRead)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.riffle.app.feature.library.LibraryItemDetailViewModelTest`
Expected: the new `markAsRead also removes...` test fails (the removal call is missing).

- [ ] **Step 3: Modify `markAsRead` to remove from To Read**

In `LibraryItemDetailViewModel.kt`, update `markAsRead()`. Existing body (verified at `LibraryItemDetailViewModel.kt:83-103`):

```kotlin
fun markAsRead() {
    viewModelScope.launch {
        repository.updateReadingProgress(itemId, 1.0f)
        sessionRepository.setProgress(itemId, 1.0f)
        val current = _uiState.value
        if (current is LibraryItemDetailUiState.Ready) {
            // invariant: ADR 0018 — Read books are never in To Read
            toReadRepository.removeFromToRead(current.item.id, current.item.libraryId)
            _uiState.value = current.copy(
                item = current.item.copy(readingProgress = 1.0f),
                isInToRead = false,
            )
        }
    }
}
```

The state-flow update is done **after** the removal call to avoid a brief inconsistent display. If the removal call fails (network), we still flip Read → true and clear `isInToRead` in the UI; the user's next visit will resync with the server. We do not surface a failure snackbar here — `markAsRead` already has no error surface today, and adding one to a side-effect would feel like Read failed when it didn't.

`markAsUnread()` is left unchanged.

- [ ] **Step 4: Run the tests until they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.riffle.app.feature.library.LibraryItemDetailViewModelTest`
Expected: all VM tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt \
        app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTest.kt
git commit -m "feat(library): markAsRead removes book from To Read (ADR 0018 invariant)"
```

---

## Task 11: Wire ToReadToggleButton into the action row + snackbar

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt`

- [ ] **Step 1: Add the button to the action row**

In `LibraryItemDetailScreen.kt`, find the action row (around line 211, where `ReadToggleButton` is composed). Insert `ToReadToggleButton` between the mark-read toggle and the `DownloadButton`:

```kotlin
ReadToggleButton(
    isRead = item.readingProgress >= READ_PROGRESS_THRESHOLD,
    onMarkAsRead = onMarkAsRead,
    onMarkAsUnread = onMarkAsUnread,
)
ToReadToggleButton(
    isInToRead = state.isInToRead,
    onAdd = onToggleToRead,
    onRemove = onToggleToRead,
)
DownloadButton(/* existing */)
```

`state` here is the `Ready` instance — pass `state.isInToRead` through whatever existing prop-drilling path the screen uses for `readingProgress`. Both `onAdd` and `onRemove` point to the same callback because the ViewModel's `toggleToRead()` already knows which direction to go from current state.

- [ ] **Step 2: Wire the callback from the ViewModel**

Add a parameter `onToggleToRead: () -> Unit` to the relevant `LibraryItemDetailContent` (or equivalent) composable. At the top-level `LibraryItemDetailScreen`, pass `viewModel::toggleToRead`. Mirror the existing `onMarkAsRead = { viewModel.markAsRead() }` pattern at `LibraryItemDetailScreen.kt:116-117`.

- [ ] **Step 3: Wire the snackbar event flow**

In `LibraryItemDetailScreen` (where `snackbarHostState` is already declared at line 120):

```kotlin
LaunchedEffect(viewModel) {
    viewModel.snackbarEvents.collect { message ->
        snackbarHostState.showSnackbar(message)
    }
}
```

Place this next to the existing `rememberCoroutineScope` / snackbar host setup.

- [ ] **Step 4: Build and install on the harness emulator to smoke-test**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Install on a connected device or the Harness AVD via `make install` and verify by hand:
- Open a Library Item Detail Screen.
- Tap the new bookmark icon — it flips to filled immediately.
- Open the Collections Tab — a `To Read` collection appears (or already exists) and contains the book.
- Re-open the detail screen — the bookmark is still filled (state survived screen recreation).
- Tap the bookmark again — flips back to outline, book is removed from the `To Read` collection on the server.
- Tap "Mark as Read" on a book that is in To Read — the bookmark flips to outline at the same time and the book is removed from the `To Read` collection server-side.
- Disconnect the device from network, tap the bookmark — UI flips optimistically, then reverts with a snackbar "Couldn't add to To Read".

If the smoke test reveals a problem that isn't covered by the existing unit tests, write a new test before fixing the bug.

- [ ] **Step 5: Run the full unit test suite**

Run: `./gradlew test`
Expected: all unit tests pass across modules.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt
git commit -m "feat(library): wire To Read button + snackbar into detail screen"
```

---

## Task 12: README feature checkbox

**Files:**
- Modify: `README.md`

Per `CLAUDE.md`: when a feature is implemented, flip its `- [ ]` to `- [x]` in the README Features list **as part of the PR for that feature**.

- [ ] **Step 1: Find the feature line in README.md**

Search the README's Features list for the line that mentions To Read / wishlist / want to read. If no such line exists yet, **add one** under the appropriate section:

```markdown
- [x] "To Read" toggle on each book (per-Library ABS Collection)
```

If a line already exists with `- [ ]`, flip it to `- [x]`.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: mark To Read toggle as implemented"
```

---

## Self-review checklist

The plan author ran this checklist before declaring the plan ready. The implementer does not need to repeat it — it's recorded here so reviewers can see what was checked.

- **Spec coverage:** Every behaviour rule in the `To Read` entry of `CONTEXT.md` and every Decision/Consequence in ADR 0018 maps to at least one task. Find-or-create by name → Task 6. Per-Library → repository takes `libraryId`, never aggregates across libraries → Tasks 5–7. Read→true removes from To Read → Task 10. Optimistic + revert + snackbar → Task 9. Empty collection left in place → Task 7's no-op-when-missing path implicitly preserves an empty collection after the last remove (we only DELETE the book, never DELETE the collection).
- **Placeholders:** Every step contains exact code or a verified file:line reference. No "TBD" / "similar to" / "add error handling" steps.
- **Type/signature consistency:** `ToReadRepository.isInToRead/addToToRead/removeFromToRead` signatures are identical across Tasks 5, 6, 7, the ViewModel, and the fake. The `(libraryItemId, libraryId)` parameter order is consistent everywhere.
- **Open questions:** Whether `material-icons-extended` is available for `Icons.Outlined.BookmarkBorder` is flagged in Task 8 with a fallback. If the implementer hits a different blocker — e.g. an `AbsLibraryApi` interface method the explore agent missed — they should STOP and report rather than guess.
