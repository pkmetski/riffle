# TOC & Chapters in Item Details — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a "Table of Contents" row (EPUB items) and "Chapters" row (audiobook items) on the Library Item Detail screen; tapping either opens a bottom sheet that lets the user jump directly to a chapter in the reader or player.

**Architecture:** Both rows are shown immediately when the detail screen opens. Background extraction fires in parallel with item load — TOC via Readium's `PublicationOpener` (using `EpubRepository.openEpub` to obtain the local file, caching from remote if needed), chapters via a new `GET /api/items/{id}` network call. Both results are persisted in Room: TOC keyed by `(serverId, itemId)` with `ebookFileIno` checked at read time for staleness; chapters keyed by `(serverId, itemId)`. The detail ViewModel exposes `StateFlow<TocState>` and `StateFlow<ChaptersState>`; the sheets show a loading spinner until data arrives.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room 2.x, Readium SDK, kotlinx.serialization, Coroutines/Flow

## Global Constraints
- Room database is currently at **version 38**; migration bumps to 39
- Composite primary keys follow the `(serverId, itemId)` pattern from the v26 migration
- TOC row is shown only for `EbookFormat.Epub` items (PDF has no Readium TOC)
- Chapters row is shown for all `isListenable` items; hidden if chapters ultimately load empty
- `Server.url` is a `ServerUrl` type — check how existing code converts it to a base URL string (grep `server.url` in data layer)
- `AudiobookChapter` is not `@Serializable` — add the annotation as part of this work
- `TocEntry` is currently in `app/` — move to `core/domain` as Task 1
- `AudiobookPlayerViewModel` already reads `startAtSec` (Float, default -1f) and seeks when `>= 0.0` — no player changes needed
- Never `adb install` unless testing yourself; run `./gradlew test` (not per-module) to verify JVM tests
- No `Co-Authored-By` trailers in commits; use `feat(scope):` Conventional Commits style
- Export `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before every `./gradlew` call

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `core/domain/.../TocEntry.kt` | **Create** (moved from app) | Domain model for EPUB TOC node |
| `core/domain/.../TocRepository.kt` | **Create** | Cache interface for TOC entries |
| `core/domain/.../AudiobookChapterCacheRepository.kt` | **Create** | Fetch + cache interface for audiobook chapters |
| `core/domain/.../AudiobookTimeline.kt` | **Modify** | Add `@Serializable` to `AudiobookChapter` |
| `core/database/.../entity/TocCacheEntity.kt` | **Create** | Room entity for cached TOC (JSON blob) |
| `core/database/.../entity/AudiobookChapterCacheEntity.kt` | **Create** | Room entity for cached chapters (JSON blob) |
| `core/database/.../dao/TocCacheDao.kt` | **Create** | get / upsert for toc_cache |
| `core/database/.../dao/AudiobookChapterCacheDao.kt` | **Create** | get / upsert for audiobook_chapter_cache |
| `core/database/.../RiffleDatabase.kt` | **Modify** | Add entities, DAOs, MIGRATION_38_39 |
| `core/database/schemas/.../39.json` | **Generated** | KSP schema export |
| `core/database/.../MigrationTest.kt` | **Modify** | Add migration38To39 test |
| `core/network/.../model/AbsItemDetailResponse.kt` | **Create** | DTO for GET /api/items/{id} |
| `core/network/.../AbsLibraryApi.kt` | **Modify** | Add `getItemDetail()` |
| `core/network/.../*AbsApiClient*.kt` | **Modify** | Implement `getItemDetail()` |
| `core/data/.../TocRepositoryImpl.kt` | **Create** | Serialize TocEntry list ↔ JSON, delegate to DAO |
| `core/data/.../AudiobookChapterCacheRepositoryImpl.kt` | **Create** | Fetch from API, serialize, delegate to DAO |
| `core/data/.../di/DataModule.kt` | **Modify** | Bind new repos + register MIGRATION_38_39 |
| `core/database/.../di/DatabaseModule.kt` | **Modify** | Provide new DAOs |
| `app/.../feature/reader/TocEntry.kt` | **Delete** | Replaced by core/domain version |
| `app/.../feature/reader/TocParser.kt` | **Modify** | Update import |
| `app/.../feature/reader/EpubReaderViewModel.kt` | **Modify** | Update import + handle startTocHref nav arg |
| `app/.../feature/library/ExtractEpubTocUseCase.kt` | **Create** | Check cache → openEpub → PublicationOpener → extract + cache |
| `app/.../feature/library/FetchAudiobookChaptersUseCase.kt` | **Create** | Check cache → getItemDetail → cache |
| `app/.../feature/library/LibraryItemDetailViewModel.kt` | **Modify** | Add tocState + chaptersState; fire background extraction |
| `app/.../feature/library/ItemTocSheet.kt` | **Create** | Bottom sheet for flattened EPUB TOC |
| `app/.../feature/library/ItemChaptersSheet.kt` | **Create** | Bottom sheet for audiobook chapter list |
| `app/.../feature/library/LibraryItemDetailScreen.kt` | **Modify** | Add TOC + Chapters rows, sheet triggers, new callbacks |
| `app/.../MainScreen.kt` | **Modify** | Add startTocHref route arg; onReadItemAtHref + onListenItemAtSec callbacks |

---

### Task 1: Move TocEntry to core/domain

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/TocEntry.kt`
- Delete: `app/src/main/kotlin/com/riffle/app/feature/reader/TocEntry.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/TocParser.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` (import only)

**Interfaces:**
- Produces: `com.riffle.core.domain.TocEntry` — used by Tasks 2, 4, 5, 7, 8

- [ ] **Step 1: Create TocEntry in core/domain**

```kotlin
// core/domain/src/main/kotlin/com/riffle/core/domain/TocEntry.kt
package com.riffle.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class TocEntry(
    val title: String,
    val href: String,
    val children: List<TocEntry> = emptyList(),
)
```

- [ ] **Step 2: Update TocParser.kt**

In `TocParser.kt` replace:
```kotlin
import com.riffle.app.feature.reader.TocEntry
```
with:
```kotlin
import com.riffle.core.domain.TocEntry
```

- [ ] **Step 3: Update EpubReaderViewModel.kt**

Replace `import com.riffle.app.feature.reader.TocEntry` with `import com.riffle.core.domain.TocEntry`. Search for all occurrences:

```bash
grep -n "com.riffle.app.feature.reader.TocEntry" app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
```

Update each one.

- [ ] **Step 4: Delete old TocEntry.kt**

```bash
rm app/src/main/kotlin/com/riffle/app/feature/reader/TocEntry.kt
```

- [ ] **Step 5: Check for any other usages**

```bash
grep -r "com.riffle.app.feature.reader.TocEntry" app/src --include="*.kt"
```

Fix any remaining imports.

- [ ] **Step 6: Verify build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "^e:|error:"
```

Expected: no errors.

- [ ] **Step 7: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/TocEntry.kt
git add app/src/main/kotlin/com/riffle/app/feature/reader/
git commit -m "refactor(domain): move TocEntry to core/domain with @Serializable"
```

---

### Task 2: Room — cache entities, DAOs, and Migration 38→39

**Files:**
- Create: `core/database/src/main/kotlin/com/riffle/core/database/entity/TocCacheEntity.kt`
- Create: `core/database/src/main/kotlin/com/riffle/core/database/entity/AudiobookChapterCacheEntity.kt`
- Create: `core/database/src/main/kotlin/com/riffle/core/database/dao/TocCacheDao.kt`
- Create: `core/database/src/main/kotlin/com/riffle/core/database/dao/AudiobookChapterCacheDao.kt`
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt`
- Modify: `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/di/DatabaseModule.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`

**Interfaces:**
- Produces: `TocCacheDao`, `AudiobookChapterCacheDao` — consumed by Task 4

- [ ] **Step 1: Write the migration test first (TDD)**

Open `MigrationTest.kt`. Add inside the test class:

```kotlin
@Test
fun migration38To39() {
    helper.createDatabase(TEST_DB, 38).use { db ->
        // Insert a library item so the full chain has data through v38
        db.execSQL("INSERT INTO library_items (serverId, id, libraryId, title, author, coverUrl, readingProgress, isCached, isDownloaded, ebookFormat, hasAudio, audioDurationSec) VALUES ('srv', 'item1', 'lib1', 'Book', 'Author', NULL, 0.0, 0, 0, 'EPUB', 0, 0.0)")
    }
    helper.runMigrationsAndValidate(TEST_DB, 39, true, RiffleDatabase.MIGRATION_38_39).use { db ->
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='toc_cache'").use { c ->
            assertTrue("toc_cache table must exist", c.moveToFirst())
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='audiobook_chapter_cache'").use { c ->
            assertTrue("audiobook_chapter_cache table must exist", c.moveToFirst())
        }
    }
}
```

Also add `RiffleDatabase.MIGRATION_38_39` to the `migrateFullChain` test's `runMigrationsAndValidate(TEST_DB, 39, true, ..., MIGRATION_38_39)` call.

Note: check the exact column list for `library_items` in v38's schema JSON at `core/database/schemas/com.riffle.core.database.RiffleDatabase/38.json` before writing the INSERT — use only columns that exist in v38.

- [ ] **Step 2: Create TocCacheEntity**

```kotlin
// core/database/src/main/kotlin/com/riffle/core/database/entity/TocCacheEntity.kt
package com.riffle.core.database.entity

import androidx.room.Entity

@Entity(tableName = "toc_cache", primaryKeys = ["serverId", "itemId"])
data class TocCacheEntity(
    val serverId: String,
    val itemId: String,
    val ebookFileIno: String,
    val entriesJson: String,  // JSON-serialized List<TocEntry>
)
```

- [ ] **Step 3: Create AudiobookChapterCacheEntity**

```kotlin
// core/database/src/main/kotlin/com/riffle/core/database/entity/AudiobookChapterCacheEntity.kt
package com.riffle.core.database.entity

import androidx.room.Entity

@Entity(tableName = "audiobook_chapter_cache", primaryKeys = ["serverId", "itemId"])
data class AudiobookChapterCacheEntity(
    val serverId: String,
    val itemId: String,
    val chaptersJson: String,  // JSON-serialized List<AudiobookChapter>
)
```

- [ ] **Step 4: Create TocCacheDao**

```kotlin
// core/database/src/main/kotlin/com/riffle/core/database/dao/TocCacheDao.kt
package com.riffle.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.riffle.core.database.entity.TocCacheEntity

@Dao
interface TocCacheDao {
    @Query("SELECT * FROM toc_cache WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun get(serverId: String, itemId: String): TocCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TocCacheEntity)
}
```

- [ ] **Step 5: Create AudiobookChapterCacheDao**

```kotlin
// core/database/src/main/kotlin/com/riffle/core/database/dao/AudiobookChapterCacheDao.kt
package com.riffle.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.riffle.core.database.entity.AudiobookChapterCacheEntity

@Dao
interface AudiobookChapterCacheDao {
    @Query("SELECT * FROM audiobook_chapter_cache WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun get(serverId: String, itemId: String): AudiobookChapterCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AudiobookChapterCacheEntity)
}
```

- [ ] **Step 6: Update RiffleDatabase.kt**

a. Bump version to 39 and add entities:
```kotlin
@Database(
    entities = [
        // ... all existing entity classes ...,
        TocCacheEntity::class,
        AudiobookChapterCacheEntity::class,
    ],
    version = 39,
)
```

b. Add DAO accessors:
```kotlin
abstract fun tocCacheDao(): TocCacheDao
abstract fun audiobookChapterCacheDao(): AudiobookChapterCacheDao
```

c. Add migration (following the `object : Migration(from, to)` pattern from existing migrations):
```kotlin
val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `toc_cache` (
                `serverId` TEXT NOT NULL,
                `itemId` TEXT NOT NULL,
                `ebookFileIno` TEXT NOT NULL,
                `entriesJson` TEXT NOT NULL,
                PRIMARY KEY(`serverId`, `itemId`)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `audiobook_chapter_cache` (
                `serverId` TEXT NOT NULL,
                `itemId` TEXT NOT NULL,
                `chaptersJson` TEXT NOT NULL,
                PRIMARY KEY(`serverId`, `itemId`)
            )"""
        )
    }
}
```

- [ ] **Step 7: Register migration in DataModule.kt**

Find the `addMigrations(...)` call and append `RiffleDatabase.MIGRATION_38_39`.

- [ ] **Step 8: Provide DAOs in DatabaseModule.kt**

```kotlin
@Provides
fun provideTocCacheDao(db: RiffleDatabase): TocCacheDao = db.tocCacheDao()

@Provides
fun provideAudiobookChapterCacheDao(db: RiffleDatabase): AudiobookChapterCacheDao =
    db.audiobookChapterCacheDao()
```

- [ ] **Step 9: Export schema**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:database:kspDebugKotlin
```

Verify `core/database/schemas/com.riffle.core.database.RiffleDatabase/39.json` was created.

- [ ] **Step 10: Commit**

```bash
git add core/database/ core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt
git commit -m "feat(database): add toc_cache and audiobook_chapter_cache tables (v38→v39)"
```

---

### Task 3: Network — GET /api/items/{id} for audiobook chapters

**Files:**
- Create: `core/network/src/main/kotlin/com/riffle/core/network/model/AbsItemDetailResponse.kt`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsLibraryApi.kt`
- Modify: the implementation class of `AbsLibraryApi` (find with `grep -r "class.*:.*AbsLibraryApi" core/network --include="*.kt"`)

**Interfaces:**
- Produces: `AbsLibraryApi.getItemDetail(baseUrl, itemId, token, insecureAllowed): AbsItemDetailResponse`

- [ ] **Step 1: Find the implementation class**

```bash
grep -r "class.*:.*AbsLibraryApi\|fun getLibraries" core/network/src/main/kotlin --include="*.kt" -l
```

Read that file to understand the HTTP client pattern (how `baseUrl`, auth token, and `insecureAllowed` are used in other methods).

- [ ] **Step 2: Create AbsItemDetailResponse.kt**

```kotlin
// core/network/src/main/kotlin/com/riffle/core/network/model/AbsItemDetailResponse.kt
package com.riffle.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AbsItemDetailResponse(
    val id: String,
    val media: AbsItemDetailMediaDto,
)

@Serializable
data class AbsItemDetailMediaDto(
    val chapters: List<AbsItemChapterDto> = emptyList(),
)

@Serializable
data class AbsItemChapterDto(
    val id: Int = 0,
    @SerialName("start") val startSec: Double = 0.0,
    @SerialName("end") val endSec: Double = 0.0,
    val title: String = "",
)
```

Note: verify the actual ABS field names for chapter start/end by querying `GET /api/items/{id}` on the test server at `http://media-server:13378` with `Authorization: Bearer <token>` (credentials in memory). The fields may be `start`/`end` or `startSec`/`endSec` — adjust `@SerialName` accordingly.

- [ ] **Step 3: Add getItemDetail to AbsLibraryApi interface**

```kotlin
suspend fun getItemDetail(
    baseUrl: String,
    itemId: String,
    token: String,
    insecureAllowed: Boolean,
): AbsItemDetailResponse
```

- [ ] **Step 4: Implement getItemDetail**

In the implementation class, follow the pattern of an existing method (e.g., `getLibraryItems`). Endpoint: `GET {baseUrl}/api/items/{itemId}`.

- [ ] **Step 5: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:network:compileDebugKotlin 2>&1 | grep -E "^e:|error:"
```

Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add core/network/
git commit -m "feat(network): add getItemDetail endpoint for audiobook chapters"
```

---

### Task 4: Repository layer — TocRepository + AudiobookChapterCacheRepository

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/TocRepository.kt`
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookChapterCacheRepository.kt`
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookTimeline.kt` (add `@Serializable` to `AudiobookChapter`)
- Create: `core/data/src/main/kotlin/com/riffle/core/data/TocRepositoryImpl.kt`
- Create: `core/data/src/main/kotlin/com/riffle/core/data/AudiobookChapterCacheRepositoryImpl.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/TocRepositoryImplTest.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/AudiobookChapterCacheRepositoryImplTest.kt`

**Interfaces:**
- Produces:
  - `TocRepository.getCachedToc(serverId, itemId): Pair<String, List<TocEntry>>?` — `(ebookFileIno, entries)` or null
  - `TocRepository.saveToc(serverId, itemId, ebookFileIno, entries: List<TocEntry>)`
  - `AudiobookChapterCacheRepository.getCachedChapters(serverId, itemId): List<AudiobookChapter>?`
  - `AudiobookChapterCacheRepository.fetchAndCacheChapters(serverId, itemId, baseUrl, token, insecureAllowed): List<AudiobookChapter>`

- [ ] **Step 1: Add @Serializable to AudiobookChapter**

In `AudiobookTimeline.kt`:
```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class AudiobookChapter(
    val index: Int,
    val startSec: Double,
    val endSec: Double,
    val title: String,
)
```

Check that `kotlinx.serialization` is a dependency of `core/domain`'s `build.gradle.kts`. If not, add it following the pattern of another module that uses it (e.g., `core/network`).

- [ ] **Step 2: Write TocRepositoryImpl tests**

```kotlin
// core/data/src/test/kotlin/com/riffle/core/data/TocRepositoryImplTest.kt
package com.riffle.core.data

import com.riffle.core.database.dao.TocCacheDao
import com.riffle.core.database.entity.TocCacheEntity
import com.riffle.core.domain.TocEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TocRepositoryImplTest {
    private val dao = mockk<TocCacheDao>()
    private val repo = TocRepositoryImpl(dao)

    @Test
    fun `getCachedToc returns null when no entry in dao`() = runTest {
        coEvery { dao.get("srv", "item") } returns null
        assertNull(repo.getCachedToc("srv", "item"))
    }

    @Test
    fun `getCachedToc returns inode and parsed entries`() = runTest {
        val json = """[{"title":"Chapter 1","href":"ch1.html","children":[]}]"""
        coEvery { dao.get("srv", "item") } returns TocCacheEntity("srv", "item", "ino42", json)
        val result = repo.getCachedToc("srv", "item")
        assertNotNull(result)
        assertEquals("ino42", result!!.first)
        assertEquals(1, result.second.size)
        assertEquals("Chapter 1", result.second[0].title)
        assertEquals("ch1.html", result.second[0].href)
    }

    @Test
    fun `saveToc upserts with correct inode and serialized JSON`() = runTest {
        coEvery { dao.upsert(any()) } returns Unit
        val entries = listOf(TocEntry("Ch 1", "c1.html"), TocEntry("Ch 2", "c2.html"))
        repo.saveToc("srv", "item", "ino99", entries)
        coVerify {
            dao.upsert(match {
                it.serverId == "srv" && it.itemId == "item" && it.ebookFileIno == "ino99" &&
                    it.entriesJson.contains("Ch 1")
            })
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test --tests "*.TocRepositoryImplTest" 2>&1 | tail -10
```

Expected: FAILED (class not found).

- [ ] **Step 4: Create TocRepository interface**

```kotlin
// core/domain/src/main/kotlin/com/riffle/core/domain/TocRepository.kt
package com.riffle.core.domain

interface TocRepository {
    suspend fun getCachedToc(serverId: String, itemId: String): Pair<String, List<TocEntry>>?
    suspend fun saveToc(serverId: String, itemId: String, ebookFileIno: String, entries: List<TocEntry>)
}
```

- [ ] **Step 5: Create TocRepositoryImpl**

Check that `kotlinx.serialization` is available in `core/data/build.gradle.kts`. If not, add it.

```kotlin
// core/data/src/main/kotlin/com/riffle/core/data/TocRepositoryImpl.kt
package com.riffle.core.data

import com.riffle.core.database.dao.TocCacheDao
import com.riffle.core.database.entity.TocCacheEntity
import com.riffle.core.domain.TocEntry
import com.riffle.core.domain.TocRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class TocRepositoryImpl @Inject constructor(
    private val dao: TocCacheDao,
) : TocRepository {

    override suspend fun getCachedToc(serverId: String, itemId: String): Pair<String, List<TocEntry>>? {
        val entity = dao.get(serverId, itemId) ?: return null
        val entries = Json.decodeFromString<List<TocEntry>>(entity.entriesJson)
        return entity.ebookFileIno to entries
    }

    override suspend fun saveToc(
        serverId: String,
        itemId: String,
        ebookFileIno: String,
        entries: List<TocEntry>,
    ) {
        dao.upsert(TocCacheEntity(serverId, itemId, ebookFileIno, Json.encodeToString(entries)))
    }
}
```

- [ ] **Step 6: Run TocRepositoryImpl tests — verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test --tests "*.TocRepositoryImplTest" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Write AudiobookChapterCacheRepositoryImpl tests**

```kotlin
// core/data/src/test/kotlin/com/riffle/core/data/AudiobookChapterCacheRepositoryImplTest.kt
package com.riffle.core.data

import com.riffle.core.database.dao.AudiobookChapterCacheDao
import com.riffle.core.database.entity.AudiobookChapterCacheEntity
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.model.AbsItemChapterDto
import com.riffle.core.network.model.AbsItemDetailMediaDto
import com.riffle.core.network.model.AbsItemDetailResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AudiobookChapterCacheRepositoryImplTest {
    private val dao = mockk<AudiobookChapterCacheDao>()
    private val api = mockk<AbsLibraryApi>()
    private val repo = AudiobookChapterCacheRepositoryImpl(dao, api)

    @Test
    fun `getCachedChapters returns null when no cache`() = runTest {
        coEvery { dao.get("srv", "item") } returns null
        assertNull(repo.getCachedChapters("srv", "item"))
    }

    @Test
    fun `getCachedChapters returns deserialized chapters`() = runTest {
        val json = """[{"index":0,"startSec":0.0,"endSec":300.0,"title":"Intro"}]"""
        coEvery { dao.get("srv", "item") } returns AudiobookChapterCacheEntity("srv", "item", json)
        val result = repo.getCachedChapters("srv", "item")
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals("Intro", result[0].title)
        assertEquals(300.0, result[0].endSec, 0.001)
    }

    @Test
    fun `fetchAndCacheChapters calls api, maps chapters, and upserts`() = runTest {
        coEvery { api.getItemDetail("http://base", "item", "tok", false) } returns
            AbsItemDetailResponse(
                id = "item",
                media = AbsItemDetailMediaDto(
                    chapters = listOf(AbsItemChapterDto(id = 0, startSec = 0.0, endSec = 600.0, title = "Ch 1"))
                ),
            )
        coEvery { dao.upsert(any()) } returns Unit

        val result = repo.fetchAndCacheChapters("srv", "item", "http://base", "tok", false)

        assertEquals(1, result.size)
        assertEquals("Ch 1", result[0].title)
        assertEquals(0, result[0].index)
        coVerify { dao.upsert(match { it.serverId == "srv" && it.itemId == "item" }) }
    }
}
```

- [ ] **Step 8: Create AudiobookChapterCacheRepository interface**

```kotlin
// core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookChapterCacheRepository.kt
package com.riffle.core.domain

interface AudiobookChapterCacheRepository {
    suspend fun getCachedChapters(serverId: String, itemId: String): List<AudiobookChapter>?
    suspend fun fetchAndCacheChapters(
        serverId: String,
        itemId: String,
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): List<AudiobookChapter>
}
```

- [ ] **Step 9: Create AudiobookChapterCacheRepositoryImpl**

```kotlin
// core/data/src/main/kotlin/com/riffle/core/data/AudiobookChapterCacheRepositoryImpl.kt
package com.riffle.core.data

import com.riffle.core.database.dao.AudiobookChapterCacheDao
import com.riffle.core.database.entity.AudiobookChapterCacheEntity
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.network.AbsLibraryApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class AudiobookChapterCacheRepositoryImpl @Inject constructor(
    private val dao: AudiobookChapterCacheDao,
    private val api: AbsLibraryApi,
) : AudiobookChapterCacheRepository {

    override suspend fun getCachedChapters(serverId: String, itemId: String): List<AudiobookChapter>? {
        val entity = dao.get(serverId, itemId) ?: return null
        return Json.decodeFromString<List<AudiobookChapter>>(entity.chaptersJson)
    }

    override suspend fun fetchAndCacheChapters(
        serverId: String,
        itemId: String,
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): List<AudiobookChapter> {
        val response = api.getItemDetail(baseUrl, itemId, token, insecureAllowed)
        val chapters = response.media.chapters.mapIndexed { index, dto ->
            AudiobookChapter(
                index = index,
                startSec = dto.startSec,
                endSec = dto.endSec,
                title = dto.title,
            )
        }
        dao.upsert(AudiobookChapterCacheEntity(serverId, itemId, Json.encodeToString(chapters)))
        return chapters
    }
}
```

- [ ] **Step 10: Bind repos in DataModule.kt**

In the `@Module` / `@InstallIn` abstract class for bindings, add:

```kotlin
@Binds abstract fun bindTocRepository(impl: TocRepositoryImpl): TocRepository
@Binds abstract fun bindAudiobookChapterCacheRepository(impl: AudiobookChapterCacheRepositoryImpl): AudiobookChapterCacheRepository
```

- [ ] **Step 11: Run all data tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 12: Commit**

```bash
git add core/domain/ core/data/
git commit -m "feat(data): TocRepository and AudiobookChapterCacheRepository with Room cache"
```

---

### Task 5: Use cases — ExtractEpubTocUseCase and FetchAudiobookChaptersUseCase

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/library/ExtractEpubTocUseCase.kt`
- Create: `app/src/main/kotlin/com/riffle/app/feature/library/FetchAudiobookChaptersUseCase.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/library/ExtractEpubTocUseCaseTest.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/library/FetchAudiobookChaptersUseCaseTest.kt`

**Interfaces:**
- Consumes: `EpubRepository`, `PublicationOpener`, `AssetRetriever`, `TocRepository`, `AudiobookChapterCacheRepository`, `ServerRepository`, `TokenStorage`
- Produces:
  - `ExtractEpubTocUseCase.invoke(item: LibraryItem): List<TocEntry>`
  - `FetchAudiobookChaptersUseCase.invoke(item: LibraryItem): List<AudiobookChapter>`

- [ ] **Step 1: Write ExtractEpubTocUseCase tests**

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/library/ExtractEpubTocUseCaseTest.kt
package com.riffle.app.feature.library

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.TocEntry
import com.riffle.core.domain.TocRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener

class ExtractEpubTocUseCaseTest {
    private val epubRepository = mockk<EpubRepository>()
    private val publicationOpener = mockk<PublicationOpener>()
    private val assetRetriever = mockk<AssetRetriever>()
    private val tocRepository = mockk<TocRepository>()
    private val useCase = ExtractEpubTocUseCase(
        epubRepository, publicationOpener, assetRetriever, tocRepository,
    )

    private fun makeItem(isCached: Boolean = true, ebookFileIno: String? = "ino1") = LibraryItem(
        id = "item1", libraryId = "lib1", title = "Book", author = "Author",
        coverUrl = null, readingProgress = 0f, isCached = isCached, isDownloaded = false,
        ebookFormat = EbookFormat.Epub, ebookFileIno = ebookFileIno, serverId = "srv1",
    )

    @Test
    fun `returns cached entries when inode matches cache`() = runTest {
        val cached = listOf(TocEntry("Chapter 1", "ch1.html"))
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("ino1" to cached)

        val result = useCase(makeItem())

        assertEquals(cached, result)
        coVerify(exactly = 0) { epubRepository.openEpub(any()) }
    }

    @Test
    fun `returns empty when openEpub fails with NetworkError`() = runTest {
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns null
        coEvery { epubRepository.openEpub(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("offline"))

        val result = useCase(makeItem())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty when item has no ebookFileIno`() = runTest {
        val result = useCase(makeItem(ebookFileIno = null))
        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { tocRepository.getCachedToc(any(), any()) }
    }
}
```

Note: testing the happy path (successful Publication open) requires mocking Readium sealed types — defer to a manual integration test on device if the mock setup is too brittle.

- [ ] **Step 2: Create ExtractEpubTocUseCase**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/library/ExtractEpubTocUseCase.kt
package com.riffle.app.feature.library

import com.riffle.app.feature.reader.TocParser.toTocEntries
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.TocEntry
import com.riffle.core.domain.TocRepository
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import javax.inject.Inject

class ExtractEpubTocUseCase @Inject constructor(
    private val epubRepository: EpubRepository,
    private val publicationOpener: PublicationOpener,
    private val assetRetriever: AssetRetriever,
    private val tocRepository: TocRepository,
) {
    suspend operator fun invoke(item: LibraryItem): List<TocEntry> {
        val inode = item.ebookFileIno ?: return emptyList()

        val cached = tocRepository.getCachedToc(item.serverId, item.id)
        if (cached != null && cached.first == inode) return cached.second

        val file = when (val r = epubRepository.openEpub(item)) {
            is EpubOpenResult.Success -> r.epubFile
            else -> return emptyList()
        }

        val url = AbsoluteUrl("file://${file.absolutePath}") ?: return emptyList()
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> return emptyList()
        }
        val publication = when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> r.value
            is Try.Failure -> return emptyList()
        }

        val entries = publication.tableOfContents.toTocEntries()
        tocRepository.saveToc(item.serverId, item.id, inode, entries)
        return entries
    }
}
```

Note: `EpubOpenResult.Success` exposes the epub file as `epubFile: File`. Confirm the field name matches the actual sealed class in `EpubRepository.kt`.

- [ ] **Step 3: Write FetchAudiobookChaptersUseCase tests**

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/library/FetchAudiobookChaptersUseCaseTest.kt
package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FetchAudiobookChaptersUseCaseTest {
    private val chapterRepo = mockk<AudiobookChapterCacheRepository>()
    private val serverRepo = mockk<ServerRepository>()
    private val tokenStorage = mockk<TokenStorage>()
    private val useCase = FetchAudiobookChaptersUseCase(chapterRepo, serverRepo, tokenStorage)

    private fun makeItem() = LibraryItem(
        id = "item1", libraryId = "lib1", title = "Book", author = "Author",
        coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
        ebookFormat = EbookFormat.Unsupported, hasAudio = true, serverId = "srv1",
    )

    @Test
    fun `returns cached chapters without hitting network`() = runTest {
        val chapters = listOf(AudiobookChapter(0, 0.0, 300.0, "Intro"))
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns chapters

        val result = useCase(makeItem())

        assertEquals(chapters, result)
        coVerify(exactly = 0) { chapterRepo.fetchAndCacheChapters(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fetches from network when no cache`() = runTest {
        val chapters = listOf(AudiobookChapter(0, 0.0, 300.0, "Intro"))
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns null
        // Use the correct Server constructor — check Server.kt for exact fields
        val server = mockk<Server>()
        coEvery { server.url.toString() } returns "http://base"  // adapt to actual ServerUrl API
        coEvery { server.insecureConnectionAllowed } returns false
        coEvery { serverRepo.getById("srv1") } returns server
        coEvery { tokenStorage.getToken("srv1") } returns "tok"
        coEvery { chapterRepo.fetchAndCacheChapters("srv1", "item1", "http://base", "tok", false) } returns chapters

        val result = useCase(makeItem())

        assertEquals(chapters, result)
    }

    @Test
    fun `returns empty list when server not found`() = runTest {
        coEvery { chapterRepo.getCachedChapters("srv1", "item1") } returns null
        coEvery { serverRepo.getById("srv1") } returns null

        val result = useCase(makeItem())

        assertTrue(result.isEmpty())
    }
}
```

Note: adapt `server.url.toString()` to whatever `Server.url` (type `ServerUrl`) exposes as a base URL string. Check how existing code in `LibraryItemDetailViewModel` or `AudiobookRepositoryImpl` constructs the base URL from a `Server` object.

- [ ] **Step 4: Create FetchAudiobookChaptersUseCase**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/library/FetchAudiobookChaptersUseCase.kt
package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import javax.inject.Inject

class FetchAudiobookChaptersUseCase @Inject constructor(
    private val chapterCacheRepository: AudiobookChapterCacheRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) {
    suspend operator fun invoke(item: LibraryItem): List<AudiobookChapter> {
        val cached = chapterCacheRepository.getCachedChapters(item.serverId, item.id)
        if (cached != null) return cached

        val server = serverRepository.getById(item.serverId) ?: return emptyList()
        val token = tokenStorage.getToken(item.serverId) ?: return emptyList()

        // Adapt server.url to a String using the same pattern as AudiobookRepositoryImpl
        val baseUrl = server.url.toString()

        return try {
            chapterCacheRepository.fetchAndCacheChapters(
                serverId = item.serverId,
                itemId = item.id,
                baseUrl = baseUrl,
                token = token,
                insecureAllowed = server.insecureConnectionAllowed,
            )
        } catch (e: Exception) {
            emptyList()
        }
    }
}
```

- [ ] **Step 5: Run tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "*.ExtractEpubTocUseCaseTest" --tests "*.FetchAudiobookChaptersUseCaseTest" 2>&1 | tail -15
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/ExtractEpubTocUseCase.kt
git add app/src/main/kotlin/com/riffle/app/feature/library/FetchAudiobookChaptersUseCase.kt
git add app/src/test/kotlin/com/riffle/app/feature/library/
git commit -m "feat(library): ExtractEpubTocUseCase and FetchAudiobookChaptersUseCase"
```

---

### Task 6: ViewModel — toc and chapters state

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTocTest.kt`

**Interfaces:**
- Produces:
  - `tocState: StateFlow<TocState>` — `Loading` initially; `Ready(entries)` after extraction
  - `chaptersState: StateFlow<ChaptersState>` — same pattern

- [ ] **Step 1: Define state sealed classes at top of the ViewModel file (or companion file)**

Add before or after the existing `LibraryItemDetailUiState`:

```kotlin
sealed interface TocState {
    data object Loading : TocState
    data class Ready(val entries: List<TocEntry>) : TocState
}

sealed interface ChaptersState {
    data object Loading : ChaptersState
    data class Ready(val chapters: List<AudiobookChapter>) : ChaptersState
}
```

- [ ] **Step 2: Write ViewModel tests**

Read an existing test file from `app/src/test/kotlin/com/riffle/app/` to understand the exact test setup (coroutine dispatcher, mock injection). The pattern uses `StandardTestDispatcher`:

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTocTest.kt
package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.TocEntry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryItemDetailViewModelTocTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // Mock all required ViewModel dependencies (match LibraryItemDetailViewModel @Inject constructor)
    // ...

    @Test
    fun `tocState transitions to Ready with entries for EPUB item`() = runTest {
        // Given: libraryRepository.getItem returns an EPUB item
        // And: extractEpubTocUseCase returns a non-empty list
        // When: ViewModel is created and dispatcher advances
        // Then: viewModel.tocState.value == TocState.Ready(entries)
    }

    @Test
    fun `chaptersState transitions to Ready for audiobook item`() = runTest {
        // Similar setup for audiobook item
    }

    @Test
    fun `tocState stays Loading for non-EPUB items`() = runTest {
        // Given: item is audiobook-only (EbookFormat.Unsupported)
        // Then: viewModel.tocState.value == TocState.Loading (use case never invoked)
    }
}
```

Flesh out the mock setup by reading the full `LibraryItemDetailViewModel` constructor and matching each injected dependency.

- [ ] **Step 3: Add injections and state flows to LibraryItemDetailViewModel**

Add to the constructor:
```kotlin
private val extractEpubTocUseCase: ExtractEpubTocUseCase,
private val fetchAudiobookChaptersUseCase: FetchAudiobookChaptersUseCase,
```

Add state flows:
```kotlin
private val _tocState = MutableStateFlow<TocState>(TocState.Loading)
val tocState: StateFlow<TocState> = _tocState.asStateFlow()

private val _chaptersState = MutableStateFlow<ChaptersState>(ChaptersState.Loading)
val chaptersState: StateFlow<ChaptersState> = _chaptersState.asStateFlow()
```

- [ ] **Step 4: Fire background extraction once item loads**

Find the point in `init` where `uiState` first transitions to `LibraryItemDetailUiState.Ready`. Immediately after that transition, launch background jobs:

```kotlin
// After uiState is set to Ready(item):
if (item.ebookFormat == EbookFormat.Epub) {
    viewModelScope.launch(Dispatchers.IO) {
        val entries = extractEpubTocUseCase(item)
        _tocState.value = TocState.Ready(entries)
    }
}
if (item.isListenable) {
    viewModelScope.launch(Dispatchers.IO) {
        val chapters = fetchAudiobookChaptersUseCase(item)
        _chaptersState.value = ChaptersState.Ready(chapters)
    }
}
```

Place this in the initial item load path, **not** inside `observeItem` (which fires on every DB update) — we only want to extract once per screen open.

- [ ] **Step 5: Run ViewModel tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "*.LibraryItemDetailViewModelTocTest" 2>&1 | tail -15
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt
git add app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModelTocTest.kt
git commit -m "feat(library): expose TocState and ChaptersState in detail ViewModel"
```

---

### Task 7: Navigation — startTocHref route param + new callbacks

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/MainScreen.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt` (add callback params only)

**Interfaces:**
- Produces:
  - `epub_reader/{itemId}?startReadaloudAtSec={}&startTocHref={}` route
  - `onReadItemAtHref: (LibraryItem, String) -> Unit` callback on detail screen
  - `onListenItemAtSec: (LibraryItem, Double) -> Unit` callback on detail screen

- [ ] **Step 1: Add startTocHref to EPUB_READER route**

In `MainScreen.kt`, find the `EPUB_READER` constant and update:
```kotlin
private const val EPUB_READER =
    "epub_reader/{itemId}?startReadaloudAtSec={startReadaloudAtSec}&startTocHref={startTocHref}"
```

In the `composable(route = EPUB_READER, ...)` block, add to the `arguments` list:
```kotlin
navArgument("startTocHref") {
    type = NavType.StringType
    nullable = true
    defaultValue = null
},
```

- [ ] **Step 2: Add navigation lambdas in MainScreen.kt**

In the `LibraryItemDetailScreen(...)` call, add:
```kotlin
onReadItemAtHref = { item, href ->
    val encodedId = URLEncoder.encode(item.id, "UTF-8")
    val encodedHref = URLEncoder.encode(href, "UTF-8")
    navController.navigate("epub_reader/$encodedId?startTocHref=$encodedHref")
},
onListenItemAtSec = { item, startSec ->
    val encodedId = URLEncoder.encode(item.id, "UTF-8")
    navController.navigate("audiobook_player/$encodedId?startAtSec=$startSec")
},
```

- [ ] **Step 3: Add callback params to LibraryItemDetailScreen**

```kotlin
@Composable
fun LibraryItemDetailScreen(
    // ... existing params ...
    onReadItemAtHref: (LibraryItem, String) -> Unit = { _, _ -> },
    onListenItemAtSec: (LibraryItem, Double) -> Unit = { _, _ -> },
    // ...
)
```

Pass these down to the content composables and ultimately to the sheet composables (added in Task 8).

- [ ] **Step 4: Handle startTocHref in EpubReaderViewModel**

Add to `EpubReaderViewModel`:
```kotlin
private val startTocHref: String? = savedStateHandle["startTocHref"]
```

Find where the reader transitions to `ReaderState.Ready` and the navigator becomes available. After that transition, if `startTocHref != null`, navigate to the matching TOC link. Follow the **same pattern as the TOC panel's tap handler** (search for where `TocEntry` click is handled in the reader screen or ViewModel — it likely emits a navigation event or calls a ViewModel function that triggers `ColumnSnap.goAndSnap(fragment, link)`):

```kotlin
startTocHref?.let { href ->
    val link = publication.tableOfContents.firstOrNull { it.href.toString() == href }
        ?: publication.readingOrder.firstOrNull { it.href.toString() == href }
    if (link != null) {
        // Emit the same navigation event used by the TOC panel tap handler
        // Check existing code: it's likely a channel or _navigateToLink StateFlow
        navigateToLink(link)
    }
}
```

Adapt to the actual event/command mechanism in the reader. Do **not** call fragment methods directly from the ViewModel.

- [ ] **Step 5: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "^e:|error:"
```

Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/MainScreen.kt
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt
git commit -m "feat(nav): startTocHref route arg + onReadItemAtHref/onListenItemAtSec callbacks"
```

---

### Task 8: UI — TOC and Chapters rows + bottom sheets

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/library/ItemTocSheet.kt`
- Create: `app/src/main/kotlin/com/riffle/app/feature/library/ItemChaptersSheet.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt`

- [ ] **Step 1: Create ItemTocSheet.kt**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/library/ItemTocSheet.kt
package com.riffle.app.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.TocEntry

private data class FlatTocEntry(val entry: TocEntry, val depth: Int)

private fun flattenToc(entries: List<TocEntry>, depth: Int = 0): List<FlatTocEntry> =
    entries.flatMap { listOf(FlatTocEntry(it, depth)) + flattenToc(it.children, depth + 1) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemTocSheet(
    entries: List<TocEntry>,
    onEntryClick: (TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = "Table of Contents",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        val flat = flattenToc(entries)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(flat, key = { "${it.depth}_${it.entry.href}" }) { (entry, depth) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onEntryClick(entry)
                            onDismiss()
                        }
                        .padding(
                            start = (16 + depth * 16).dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp,
                        ),
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (depth == 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
```

- [ ] **Step 2: Create ItemChaptersSheet.kt**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/library/ItemChaptersSheet.kt
package com.riffle.app.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.AudiobookChapter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemChaptersSheet(
    chapters: List<AudiobookChapter>,
    onChapterClick: (AudiobookChapter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = "Chapters",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(chapters, key = { it.index }) { chapter ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onChapterClick(chapter)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = chapter.startSec.toTimestamp(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = (chapter.endSec - chapter.startSec).toDuration(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun Double.toTimestamp(): String {
    val h = (this / 3600).toInt()
    val m = ((this % 3600) / 60).toInt()
    val s = (this % 60).roundToInt()
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun Double.toDuration(): String {
    val totalMin = (this / 60).roundToInt()
    return if (totalMin < 60) "${totalMin}m"
    else "${totalMin / 60}h ${totalMin % 60}m"
}
```

- [ ] **Step 3: Add rows and sheets to LibraryItemDetailScreen**

In the main scroll content (and its landscape/tablet variants), after the action row divider, add:

```kotlin
// Collect state
val tocState by viewModel.tocState.collectAsState()
val chaptersState by viewModel.chaptersState.collectAsState()
var showTocSheet by remember { mutableStateOf(false) }
var showChaptersSheet by remember { mutableStateOf(false) }
```

After the actions `HorizontalDivider()`:

```kotlin
// TOC row — EPUB items only
if (item.ebookFormat == EbookFormat.Epub) {
    ListItem(
        headlineContent = { Text("Table of Contents") },
        supportingContent = {
            when (val s = tocState) {
                is TocState.Loading -> Text("Loading…")
                is TocState.Ready -> Text("${s.entries.size} sections")
            }
        },
        leadingContent = {
            Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = null)
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null)
        },
        modifier = Modifier.clickable(
            enabled = tocState is TocState.Ready && (tocState as TocState.Ready).entries.isNotEmpty(),
            onClick = { showTocSheet = true },
        ),
    )
    HorizontalDivider()
}

// Chapters row — audiobook items; hidden if chapters loaded as empty
val chaptersReady = chaptersState as? ChaptersState.Ready
if (item.isListenable && (chaptersState is ChaptersState.Loading || chaptersReady?.chapters?.isNotEmpty() == true)) {
    ListItem(
        headlineContent = { Text("Chapters") },
        supportingContent = {
            when (val s = chaptersState) {
                is ChaptersState.Loading -> Text("Loading…")
                is ChaptersState.Ready -> Text("${s.chapters.size} chapters")
            }
        },
        leadingContent = {
            Icon(Icons.Outlined.Headphones, contentDescription = null)
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null)
        },
        modifier = Modifier.clickable(
            enabled = chaptersState is ChaptersState.Ready && chaptersReady?.chapters?.isNotEmpty() == true,
            onClick = { showChaptersSheet = true },
        ),
    )
    HorizontalDivider()
}

// Sheets
if (showTocSheet) {
    val entries = (tocState as? TocState.Ready)?.entries ?: emptyList()
    ItemTocSheet(
        entries = entries,
        onEntryClick = { entry -> onReadItemAtHref(item, entry.href) },
        onDismiss = { showTocSheet = false },
    )
}
if (showChaptersSheet) {
    val chapters = (chaptersState as? ChaptersState.Ready)?.chapters ?: emptyList()
    ItemChaptersSheet(
        chapters = chapters,
        onChapterClick = { chapter -> onListenItemAtSec(item, chapter.startSec) },
        onDismiss = { showChaptersSheet = false },
    )
}
```

Note: check available icon names with `grep -r "Icons\." app/src/main/kotlin --include="*.kt" | head -20` to match the icons already used in the project. Substitute unavailable icon names with the closest alternative.

Propagate the new `showTocSheet`/`showChaptersSheet` state and sheet composables to `LibraryItemDetailContentPhoneLandscape` and `LibraryItemDetailContentTablet` if they render the same content list independently.

- [ ] **Step 4: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "^e:|error:"
```

Expected: no errors.

- [ ] **Step 5: Run full JVM test suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/ItemTocSheet.kt
git add app/src/main/kotlin/com/riffle/app/feature/library/ItemChaptersSheet.kt
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt
git commit -m "feat(library): TOC and Chapters rows with bottom sheet navigation in item detail"
```
