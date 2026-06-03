# ABS-side Readaloud Auto-Matching + Reader Auto-Play Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make exact ABS↔Storyteller readaloud matches link automatically when an ABS library loads (no Storyteller-library visit), and make the reader's readaloud control start playback immediately on press.

**Architecture:** Introduce a `@Singleton` `StorytellerReadaloudSyncer` that best-effort fetches every Storyteller server's readaloud books into the local DB, throttled to once per server per 10 min. Call it from the ABS branch of `LibraryRepositoryImpl.refreshLibraryItems(...)` right before the existing `reconcileLinks()`, so the unchanged matcher links exact matches against freshly-synced data. A shared pure mapping function (`storytellerBooksToEntities`) is used by both the syncer and the existing active-server Storyteller refresh (DRY). Separately, `EpubReaderViewModel.openReadaloud()` also triggers the existing play path.

**Tech Stack:** Kotlin, Hilt, coroutines/Flow, JUnit4 + kotlinx-coroutines-test (`runTest`), pure-JVM unit tests under `core/data/src/test`.

**Spec:** `docs/superpowers/specs/2026-06-03-abs-side-readaloud-matching-and-autoplay-design.md`

---

## File Structure

**Create:**
- `core/data/src/main/kotlin/com/riffle/core/data/StorytellerReadaloudSyncer.kt` — proactive, throttled, best-effort Storyteller readaloud fetch+store; plus the shared `storytellerBooksToEntities` mapping.
- `core/data/src/test/kotlin/com/riffle/core/data/StorytellerReadaloudSyncerTest.kt`

**Modify:**
- `core/data/src/main/kotlin/com/riffle/core/data/LibraryRepositoryImpl.kt` — inject the syncer; call `syncStale()` in the ABS branch before `reconcileLinks()`; refactor `refreshStorytellerReadalouds` to reuse `storytellerBooksToEntities`.
- `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt` — `@Provides @Singleton` for `StorytellerReadaloudSyncer` (wires the `clock`).
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` — `openReadaloud()` starts playback.

---

## Task 1: Shared `storytellerBooksToEntities` mapping (DRY extraction, no behavior change)

Extract the Storyteller-book→entity mapping (currently inline in `refreshStorytellerReadalouds`) into one shared function so the new syncer and the existing path build identical rows (same title/author/isbn/asin/cover/progress preservation — the matcher depends on these).

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/StorytellerReadaloudSyncer.kt` (mapping only for now)
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/LibraryRepositoryImpl.kt` (`refreshStorytellerReadalouds`)
- Test: `core/data/src/test/kotlin/com/riffle/core/data/StorytellerReadaloudSyncerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBook
import org.junit.Assert.assertEquals
import org.junit.Test

class StorytellerReadaloudSyncerTest {
    @Test fun `mapping builds entities preserving identifiers and local progress`() {
        val books = listOf(
            NetworkStorytellerBook(id = 42L, title = "Dune", authors = listOf("Frank Herbert", "Brian Herbert"), isbn = "111", asin = "B01"),
        )
        val entities = storytellerBooksToEntities(
            books = books,
            libraryId = "readaloud:st-1",
            coverUrlOf = { id -> "http://s/api/books/$id/cover" },
            lastOpenedAtMap = mapOf("42" to 1234L),
            progressMap = mapOf("42" to 0.5f),
        )
        assertEquals(1, entities.size)
        val e = entities[0]
        assertEquals("42", e.id)
        assertEquals("readaloud:st-1", e.libraryId)
        assertEquals("Dune", e.title)
        assertEquals("Frank Herbert, Brian Herbert", e.author)
        assertEquals("111", e.isbn)
        assertEquals("B01", e.asin)
        assertEquals("http://s/api/books/42/cover", e.coverUrl)
        assertEquals(0.5f, e.readingProgress)
        assertEquals(1234L, e.lastOpenedAt)
    }
}
```

> Before writing, open `core/network/.../StorytellerApi.kt` (or wherever `NetworkStorytellerBook` is defined) and confirm its constructor params/names (`id: Long`, `title`, `authors: List<String>`, `isbn`, `asin`). Adjust the test's constructor call to match the real fields exactly (some may be nullable / have defaults).

- [ ] **Step 2: Run it; verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:data:test --tests "com.riffle.core.data.StorytellerReadaloudSyncerTest"`
Expected: FAIL — unresolved `storytellerBooksToEntities`.

- [ ] **Step 3: Create the mapping function**

In the new file `StorytellerReadaloudSyncer.kt`:

```kotlin
package com.riffle.core.data

import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.domain.EbookFormat
import com.riffle.core.network.NetworkStorytellerBook

/**
 * Builds local [LibraryItemEntity] rows from Storyteller readaloud books. Shared by the
 * active-server refresh ([LibraryRepositoryImpl.refreshStorytellerReadalouds]) and the proactive
 * [StorytellerReadaloudSyncer] so both produce identical rows (the matcher keys off
 * title/author/isbn/asin). Existing local reading progress and last-opened timestamps are merged
 * back in so a refresh never resets them.
 */
internal fun storytellerBooksToEntities(
    books: List<NetworkStorytellerBook>,
    libraryId: String,
    coverUrlOf: (Long) -> String,
    lastOpenedAtMap: Map<String, Long?>,
    progressMap: Map<String, Float>,
): List<LibraryItemEntity> = books.map { book ->
    val id = book.id.toString()
    LibraryItemEntity(
        id = id,
        libraryId = libraryId,
        title = book.title,
        author = book.authors.joinToString(", "),
        coverUrl = coverUrlOf(book.id),
        readingProgress = progressMap[id] ?: 0f,
        ebookFormat = EbookFormat.Epub.toStorageString(),
        ebookFileIno = null,
        description = null,
        seriesName = null,
        publishedYear = null,
        genres = "",
        publisher = null,
        lastOpenedAt = lastOpenedAtMap[id],
        addedAt = null,
        isbn = book.isbn,
        asin = book.asin,
    )
}
```

> Match the real `LibraryItemEntity` field set exactly (copy the property list from the existing inline mapping in `refreshStorytellerReadalouds`, ~lines 250–270). If a field name differs (e.g. `ebookFileIno`), use the real one. `EbookFormat.toStorageString()` is the existing extension used there.

- [ ] **Step 4: Refactor `refreshStorytellerReadalouds` to use it (no behavior change)**

In `LibraryRepositoryImpl.refreshStorytellerReadalouds` replace the inline `result.books.map { ... }` block with:

```kotlin
        val entities = storytellerBooksToEntities(
            books = result.books,
            libraryId = libraryId,
            coverUrlOf = { bookId -> storytellerApi.coverUrl(server.url.value, bookId) },
            lastOpenedAtMap = lastOpenedAtMap,
            progressMap = localProgressMap,
        )
```

(Keep the surrounding `getLastOpenedAtMap`/`getReadingProgressMap` reads, the `replaceAllForLibrary`, `setUnsupported`, and `reconcileLinks()` exactly as they are.)

- [ ] **Step 5: Run mapping test + existing Storyteller refresh test**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:data:test --tests "com.riffle.core.data.StorytellerReadaloudSyncerTest" --tests "com.riffle.core.data.LibraryRepositoryTest"`
Expected: PASS (the existing `refreshLibraryItems for Storyteller server populates library_items...` test guards the refactor).

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/StorytellerReadaloudSyncer.kt core/data/src/main/kotlin/com/riffle/core/data/LibraryRepositoryImpl.kt core/data/src/test/kotlin/com/riffle/core/data/StorytellerReadaloudSyncerTest.kt
git commit -m "refactor(readaloud): shared storytellerBooksToEntities mapping"
```
Repo convention: NO "Co-Authored-By: Claude" trailer, NO "Generated with Claude Code" footer.

---

## Task 2: `StorytellerReadaloudSyncer` — fetch+store + throttled `syncStale`

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/StorytellerReadaloudSyncer.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/StorytellerReadaloudSyncerTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `StorytellerReadaloudSyncerTest`:

```kotlin
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkStorytellerBooksResult
import com.riffle.core.network.StorytellerLibraryApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun stServer(id: String) = Server(
    id = id, url = ServerUrl.parse("http://st-$id:8001")!!, isActive = false,
    insecureConnectionAllowed = false, username = "u", serverType = ServerType.STORYTELLER,
)
private fun absServer(id: String) = stServer(id).copy(serverType = ServerType.AUDIOBOOKSHELF)

private class FakeServers(servers: List<Server>) : ServerRepository {
    private val all = servers
    override fun observeAll(): Flow<List<Server>> = flowOf(all)
    override suspend fun getActive(): Server? = all.firstOrNull { it.isActive }
    override suspend fun getById(serverId: String): Server? = all.firstOrNull { it.id == serverId }
    // The rest are unused here; throw so accidental use is loud.
    override suspend fun authenticate(p0: com.riffle.core.domain.ServerUrl, p1: String, p2: String, p3: Boolean) = error("unused")
    override suspend fun commit(pending: com.riffle.core.domain.PendingServer, hiddenLibraryIds: Set<String>) = error("unused")
    override suspend fun setActive(serverId: String) = error("unused")
    override suspend fun remove(serverId: String) = error("unused")
    override suspend fun getServerVersion(serverId: String): String? = null
}
private class FakeTokens(private val t: Map<String, String>) : TokenStorage {
    override suspend fun getToken(serverId: String): String? = t[serverId]
    override suspend fun saveToken(serverId: String, token: String) = error("unused")
    override suspend fun clearToken(serverId: String) = error("unused")
}
private class CapturingApi(
    private val result: (String) -> NetworkStorytellerBooksResult,
) : StorytellerLibraryApi {
    val calls = mutableListOf<String>()
    override suspend fun listReadalouds(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkStorytellerBooksResult {
        calls += baseUrl; return result(baseUrl)
    }
    override suspend fun validateToken(baseUrl: String, token: String, insecureAllowed: Boolean) = error("unused")
    override suspend fun getBook(baseUrl: String, bookId: Long, token: String, insecureAllowed: Boolean) = error("unused")
    override fun coverUrl(baseUrl: String, bookId: Long) = "$baseUrl/api/books/$bookId/cover"
}

private fun booksOk(vararg books: NetworkStorytellerBook) = NetworkStorytellerBooksResult.Success(books.toList())

@Test fun `syncStale fetches each storyteller server and stores under readaloud library id`() = runTest {
    val itemDao = FakeLibraryItemDao()
    val api = CapturingApi { booksOk(NetworkStorytellerBook(id = 1L, title = "T", authors = listOf("A"))) }
    val syncer = StorytellerReadaloudSyncer(
        serverRepository = FakeServers(listOf(stServer("st-1"), absServer("abs-1"))),
        tokenStorage = FakeTokens(mapOf("st-1" to "tok")),
        storytellerApi = api,
        libraryItemDao = itemDao,
        clock = { 0L },
    )
    syncer.syncStale()
    assertEquals(listOf("http://st-st-1:8001"), api.calls) // only the storyteller server
    assertEquals(1, itemDao.itemsFor("readaloud:st-1").size)
}

@Test fun `syncStale respects the staleness ttl per server`() = runTest {
    val itemDao = FakeLibraryItemDao()
    val api = CapturingApi { booksOk(NetworkStorytellerBook(id = 1L, title = "T", authors = listOf("A"))) }
    var now = 0L
    val syncer = StorytellerReadaloudSyncer(
        FakeServers(listOf(stServer("st-1"))), FakeTokens(mapOf("st-1" to "tok")), api, itemDao, clock = { now },
    )
    syncer.syncStale()
    now = 9 * 60 * 1000L           // < 10 min
    syncer.syncStale()
    assertEquals(1, api.calls.size) // throttled, no second fetch
    now = 11 * 60 * 1000L          // > 10 min
    syncer.syncStale()
    assertEquals(2, api.calls.size)
}

@Test fun `syncStale is best-effort - a failing server does not throw and is not recorded`() = runTest {
    val itemDao = FakeLibraryItemDao()
    val api = CapturingApi { NetworkStorytellerBooksResult.NetworkError(java.io.IOException("down")) }
    var now = 0L
    val syncer = StorytellerReadaloudSyncer(
        FakeServers(listOf(stServer("st-1"))), FakeTokens(mapOf("st-1" to "tok")), api, itemDao, clock = { now },
    )
    syncer.syncStale() // must not throw
    assertEquals(0, itemDao.itemsFor("readaloud:st-1").size)
    now = 1L
    syncer.syncStale() // not recorded as synced → retried immediately
    assertEquals(2, api.calls.size)
}
```

> Reuse the existing `FakeLibraryItemDao` from `LibraryRepositoryTest.kt`. If it's `private` to that file, move it (and only it) into a new shared `core/data/src/test/kotlin/com/riffle/core/data/FakeLibraryItemDao.kt` as `internal class FakeLibraryItemDao` so both tests use it. Add a small helper to it if missing: `fun itemsFor(libraryId: String): List<LibraryItemEntity>` returning what was last stored for that library (it already captures writes — expose them per library). Verify the real `ServerRepository`/`TokenStorage`/`StorytellerLibraryApi` member signatures and fix the fakes above to match exactly (param names, any `suspend`, default methods).

- [ ] **Step 2: Run; verify failure**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:data:test --tests "com.riffle.core.data.StorytellerReadaloudSyncerTest"`
Expected: FAIL — `StorytellerReadaloudSyncer` constructor/`syncStale` not defined.

- [ ] **Step 3: Implement the syncer**

Add to `StorytellerReadaloudSyncer.kt`:

```kotlin
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkStorytellerBooksResult
import com.riffle.core.network.StorytellerLibraryApi
import kotlinx.coroutines.flow.first

/** Local readaloud library id scheme — must match ServerRepositoryImpl.readaloudLibraryId. */
internal fun readaloudLibraryId(serverId: String): String = "readaloud:$serverId"

private const val STORYTELLER_SYNC_TTL_MILLIS = 10L * 60L * 1000L

/**
 * Best-effort, throttled proactive sync of every Storyteller server's readaloud books into the
 * local DB, so the ABS-side auto-matcher can link exact matches without the user ever opening the
 * Readaloud library. Never throws; a server that errors is skipped and retried next time.
 */
class StorytellerReadaloudSyncer(
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val storytellerApi: StorytellerLibraryApi,
    private val libraryItemDao: LibraryItemDao,
    private val clock: () -> Long,
    private val ttlMillis: Long = STORYTELLER_SYNC_TTL_MILLIS,
) {
    private val lastSyncedAt = mutableMapOf<String, Long>()

    suspend fun syncStale() {
        val servers = runCatching { serverRepository.observeAll().first() }.getOrNull().orEmpty()
            .filter { it.serverType == ServerType.STORYTELLER }
        val now = clock()
        for (server in servers) {
            val last = lastSyncedAt[server.id]
            if (last != null && now - last < ttlMillis) continue
            val token = tokenStorage.getToken(server.id) ?: continue
            val ok = runCatching { fetchAndStore(server, token) }.getOrDefault(false)
            if (ok) lastSyncedAt[server.id] = now
        }
    }

    private suspend fun fetchAndStore(server: Server, token: String): Boolean {
        val libraryId = readaloudLibraryId(server.id)
        return when (val r = storytellerApi.listReadalouds(server.url.value, token, server.insecureConnectionAllowed)) {
            is NetworkStorytellerBooksResult.Success -> {
                val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(libraryId).associate { it.id to it.lastOpenedAt }
                val progressMap = libraryItemDao.getReadingProgressMap(libraryId).associate { it.id to it.readingProgress }
                val entities = storytellerBooksToEntities(
                    books = r.books,
                    libraryId = libraryId,
                    coverUrlOf = { bookId -> storytellerApi.coverUrl(server.url.value, bookId) },
                    lastOpenedAtMap = lastOpenedAtMap,
                    progressMap = progressMap,
                )
                libraryItemDao.replaceAllForLibrary(libraryId, entities)
                true
            }
            is NetworkStorytellerBooksResult.NetworkError -> false
        }
    }
}
```

> Confirm `getLastOpenedAtMap`/`getReadingProgressMap` return shapes from `LibraryItemDao` and that `.associate { it.id to it.lastOpenedAt }` matches the existing usage in `refreshStorytellerReadalouds`; copy that exact form.

- [ ] **Step 4: Provide it via Hilt**

In `DataModule.kt` add (in the `@Module`/`object` that has `@Provides` functions; if the module is an `abstract class` with `@Binds`, add a companion `object` or a sibling `@Module object` for this `@Provides`):

```kotlin
    @Provides
    @Singleton
    fun provideStorytellerReadaloudSyncer(
        serverRepository: ServerRepository,
        tokenStorage: TokenStorage,
        storytellerApi: StorytellerLibraryApi,
        libraryItemDao: LibraryItemDao,
    ): StorytellerReadaloudSyncer = StorytellerReadaloudSyncer(
        serverRepository = serverRepository,
        tokenStorage = tokenStorage,
        storytellerApi = storytellerApi,
        libraryItemDao = libraryItemDao,
        clock = System::currentTimeMillis,
    )
```

> Match the module's existing style/imports. `StorytellerLibraryApi`, `LibraryItemDao`, `ServerRepository`, `TokenStorage` are already provided elsewhere in DI (they're constructor-injected into `LibraryRepositoryImpl`), so no new bindings are needed.

- [ ] **Step 5: Run tests**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:data:test`
Expected: PASS (all syncer tests + existing suite).

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/StorytellerReadaloudSyncer.kt core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt core/data/src/test/kotlin/com/riffle/core/data
git commit -m "feat(readaloud): proactive throttled Storyteller readaloud syncer"
```

---

## Task 3: Hook the syncer into ABS library load

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/LibraryRepositoryImpl.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/LibraryRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `LibraryRepositoryTest` (mirror its existing setup helpers/fakes). The intent: an ABS library refresh first syncs Storyteller readalouds, then reconciles, so an exact match auto-links — with NO Storyteller-library refresh call.

```kotlin
@Test fun `ABS library refresh proactively syncs storyteller readalouds before reconciling`() = runTest {
    // ABS active server with one ABS book; a Storyteller server whose readaloud exactly matches it.
    fakeServerRepository.activeServer = absServer()           // existing helper
    fakeServerRepository.allServers = listOf(absServer(), storytellerServer()) // ensure observeAll() returns both
    fakeTokenStorage.tokens["abs-1"] = "tok-abs"
    fakeTokenStorage.tokens["st-1"] = "tok-st"

    val itemDao = FakeLibraryItemDao()
    val absApi = absApiReturning(/* one ABS item: title="Dune", author="Frank Herbert", isbn="X" */)
    val storyteller = storytellerApiReturning(listOf(
        NetworkStorytellerBook(id = 7L, title = "Dune", authors = listOf("Frank Herbert"), isbn = "X"),
    ))
    val linkDao = FakeReadaloudLinkDao()
    val repo = makeRepo(
        libraryItemDao = itemDao,
        api = absApi,
        storytellerApi = storyteller,
        readaloudMatchingService = realMatchingService(itemDao, linkDao), // real matcher so exact match links
        storytellerReadaloudSyncer = StorytellerReadaloudSyncer(
            fakeServerRepository, fakeTokenStorage, storyteller, itemDao, clock = { 0L },
        ),
    )

    val result = repo.refreshLibraryItems("abs-lib-1")

    assertTrue(result is LibraryRefreshResult.Success)
    // Storyteller readaloud was fetched and stored without opening its library:
    assertEquals(1, itemDao.itemsFor("readaloud:st-1").size)
    // And the exact match auto-linked:
    assertEquals(1, linkDao.allRows().size)
}
```

> This test wires several real/fake pieces; adapt names to the existing `LibraryRepositoryTest` helpers. If a `realMatchingService` helper doesn't exist, construct a real `ReadaloudMatchingService` with the fake DAOs (the file already builds matchable rows via `FakeLibraryItemDao.listMatchableByServerType`). If that wiring is too heavy, split the assertion: (a) a focused test that `refreshLibraryItems` (ABS) calls `syncer.syncStale()` using a spy syncer, and (b) rely on Task 2's tests for the sync itself + existing matcher tests for linking. Prefer the spy-syncer version if the real-matcher wiring is unwieldy:

```kotlin
@Test fun `ABS library refresh invokes storyteller syncer before reconcile`() = runTest {
    fakeServerRepository.activeServer = absServer()
    fakeTokenStorage.tokens["abs-1"] = "tok-abs"
    val itemDao = FakeLibraryItemDao()
    var synced = false
    val spySyncer = object : StorytellerReadaloudSyncer(fakeServerRepository, fakeTokenStorage, storytellerApiReturning(emptyList()), itemDao, { 0L }) {
        override suspend fun syncStale() { synced = true }
    }
    val repo = makeRepo(libraryItemDao = itemDao, api = absApiReturning(/* one item */), storytellerReadaloudSyncer = spySyncer)
    repo.refreshLibraryItems("abs-lib-1")
    assertTrue(synced)
}
```

> For the spy to work, `syncStale()` must be `open` (or `StorytellerReadaloudSyncer` an interface). Simplest: mark `class StorytellerReadaloudSyncer` `open` and `open suspend fun syncStale()`. Do that in Task 2's class if you anticipate the spy; otherwise extract an interface. Pick one and keep it consistent.

- [ ] **Step 2: Run; verify failure**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:data:test --tests "com.riffle.core.data.LibraryRepositoryTest"`
Expected: FAIL — `makeRepo` has no `storytellerReadaloudSyncer` param / constructor mismatch.

- [ ] **Step 3: Inject + call the syncer**

In `LibraryRepositoryImpl`'s constructor, add the dependency (after `readaloudMatchingService`):

```kotlin
    private val storytellerReadaloudSyncer: StorytellerReadaloudSyncer,
```

In `refreshLibraryItems`, in the ABS success branch, immediately BEFORE the existing `readaloudMatchingService.reconcileLinks()` call (line ~175), add:

```kotlin
            storytellerReadaloudSyncer.syncStale()
            readaloudMatchingService.reconcileLinks()
```

(`syncStale()` is best-effort and never throws, so it cannot break the ABS refresh. Do NOT add it to the Storyteller branch — that path already fetches its own books.)

Update the test `makeRepo(...)` helper to pass a default `storytellerReadaloudSyncer` (a real one built from the fakes, or the spy).

- [ ] **Step 4: Run tests**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:data:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/LibraryRepositoryImpl.kt core/data/src/test/kotlin/com/riffle/core/data/LibraryRepositoryTest.kt
git commit -m "feat(readaloud): sync storyteller readalouds on ABS library load"
```

---

## Task 4: Reader auto-play on the readaloud control

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

- [ ] **Step 1: Make `openReadaloud()` start playback**

Current body:

```kotlin
    fun openReadaloud() {
        if (!_readaloudAvailable.value) return
        _readaloudOpen.value = true
        startStorytellerSync()
    }
```

Change to:

```kotlin
    fun openReadaloud() {
        if (!_readaloudAvailable.value) return
        _readaloudOpen.value = true
        startStorytellerSync()
        // Pressing the reader's readaloud control plays immediately — no separate Play tap.
        // For a matched ABS book the control is only enabled once the bundle is present, so this
        // reaches the bundle-present play path; for a Storyteller book it matches the Play tap.
        onPlayTapped()
    }
```

(`onPlayTapped()` is the existing play entry point already defined in this VM.)

- [ ] **Step 2: Compile**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

> No unit test: `EpubReaderViewModel` is an `AndroidViewModel` with many dependencies and is not unit-tested in this repo; a playback assertion on the emulator is flaky (audio-HAL). This one-line change calls an already-tested function. It is verified by compile + the manual smoke in Task 5.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(readaloud): reader control starts playback on press"
```

---

## Task 5: Full build + test sweep

- [ ] **Step 1: Unit suite**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Harness UI suite**

Run: `make harness-test`
Expected: PASS (no regressions; this feature adds no new instrumented tests). If a flaky failure appears (e.g. `SearchHarnessTest`, audio-HAL), re-run once to confirm it's environmental.

- [ ] **Step 3: Manual smoke (real app)**

Against ABS (`http://media-server:13378`, test/test) and a Storyteller server (`http://media-server:8001`):
- Fresh launch → open an ABS library **without** visiting the Readaloud library → confirm books with an exact Storyteller match now show the readaloud indicator + download button (auto-matched).
- Bounce in/out of ABS libraries repeatedly → confirm it doesn't re-hit the Storyteller server every time (throttled).
- Open a matched ABS book (bundle downloaded), tap the reader top-bar readaloud control → audio **starts playing immediately**, no Play tap.

- [ ] **Step 4: Commit any fixes**

```bash
git add -A && git commit -m "test(readaloud): fixes from ABS-side matching + autoplay sweep"
```

---

## Self-Review Notes

- **Spec coverage:** Part 1 trigger/throttle/best-effort/multi-server → Tasks 2–3; exact-match auto-link via unchanged `reconcileLinks` → Task 3; fuzzy untouched (no matcher change anywhere). Part 2 auto-play → Task 4. DRY mapping → Task 1.
- **Type consistency:** `storytellerBooksToEntities(books, libraryId, coverUrlOf, lastOpenedAtMap, progressMap)` and `readaloudLibraryId(serverId)` used identically across Tasks 1–3; `StorytellerReadaloudSyncer(serverRepository, tokenStorage, storytellerApi, libraryItemDao, clock, ttlMillis=…)` and `syncStale()` consistent across Tasks 2–3; the `open class`/spy decision must be made once in Task 2 and used in Task 3.
- **Risk:** Task 1 refactors a tested path — the existing Storyteller-refresh test is the guard. Task 3's full real-matcher test may be heavy; the spy-syncer fallback is provided. Confirm `NetworkStorytellerBook` and `LibraryItemEntity` field names against source before writing tests (flagged inline).
