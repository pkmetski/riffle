# Annotations Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-book annotations panel to the EPUB reader listing highlights, notes, and bookmarks in reading-position order, with tap-to-navigate and delete/rename from the list.

**Architecture:** DB migration adds three columns to `annotations` (`spineIndex`, `progression`, `bookmarkTitle`). The domain store gains `observeAnnotations()` and `renameBookmark()`. The reader ViewModel wires these into a panel-visible state and a navigation channel. A new `AnnotationsPanel` composable (a `ModalBottomSheet` like `TocPanel`) is added to `EpubReaderScreen` alongside a new toolbar button.

**Tech Stack:** Room (SQLite migrations), Kotlin Coroutines/Flow, Jetpack Compose / Material3, Readium Locator API, Hilt DI.

## Global Constraints

- DB version bumps from 37 → 38; migration is `MIGRATION_37_38` in `RiffleDatabase.kt`.
- All new columns use `NOT NULL DEFAULT` so existing rows are valid without a full table rebuild.
- No changes to audiobook player code (`PlayerListSheet`, `AudiobookPlayerViewModel`, etc.).
- `TocPanel` is `ModalBottomSheet`; `AnnotationsPanel` matches that pattern exactly.
- JVM tests run via `./gradlew test` (not `:testDebugUnitTest`); set `JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home`.
- Migration test is an Android instrumented test (`core/database`); run it pinned to a Harness AVD, not via `make harness-test` (see CLAUDE.md).

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `core/database/…/AnnotationEntity.kt` | Modify | Add `spineIndex`, `progression`, `bookmarkTitle` columns |
| `core/database/…/AnnotationDao.kt` | Modify | Add position-ordered query + `renameBookmark` update |
| `core/database/…/RiffleDatabase.kt` | Modify | Bump version 37→38, add `MIGRATION_37_38` |
| `core/data/…/di/DatabaseModule.kt` | Modify | Register `MIGRATION_37_38` |
| `core/database/…/MigrationTest.kt` | Modify | Add `migration37To38` test + extend `migrateFullChain` |
| `core/domain/…/Annotation.kt` | Modify | Add `spineIndex`, `progression`, `bookmarkTitle` fields |
| `core/domain/…/AnnotationStore.kt` | Modify | Add `observeAnnotations()` + `renameBookmark()` + update `createBookmark()` signature |
| `core/data/…/AnnotationStoreImpl.kt` | Modify | Implement new methods, update `toDomain()`, update `createBookmark()` |
| `core/data/…/AnnotationStoreImplTest.kt` | Modify | Update fake DAO + add tests for new methods |
| `app/…/reader/EpubBookmarkTitleBuilder.kt` | Create | Pure function: chapter-title + progression default title |
| `app/src/test/…/reader/EpubBookmarkTitleBuilderTest.kt` | Create | Unit tests for all title paths |
| `app/…/reader/EpubReaderViewModel.kt` | Modify | Panel state, `annotations` flow, navigation channel, rename |
| `app/…/reader/AnnotationsPanel.kt` | Create | `ModalBottomSheet` with mixed highlight/bookmark rows |
| `app/…/reader/EpubReaderScreen.kt` | Modify | Toolbar icon, panel wiring, navigation `LaunchedEffect` |

---

## Task 1: DB layer — migration 37→38

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/AnnotationEntity.kt`
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/AnnotationDao.kt`
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt`
- Test: `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`

**Interfaces:**
- Produces: `AnnotationEntity` with fields `spineIndex: Int = 0`, `progression: Double = 0.0`, `bookmarkTitle: String = ""`
- Produces: `AnnotationDao.observeAnnotationsByPosition(serverId, itemId): Flow<List<AnnotationEntity>>`
- Produces: `AnnotationDao.renameBookmark(id, title, updatedAt, deviceId)`
- Produces: `RiffleDatabase.MIGRATION_37_38`

- [ ] **Step 1: Add three columns to `AnnotationEntity`**

Replace the class body in `core/database/src/main/kotlin/com/riffle/core/database/AnnotationEntity.kt`:

```kotlin
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val itemId: String,
    val type: String = TYPE_HIGHLIGHT,
    val cfi: String,
    val color: String = COLOR_YELLOW,
    val note: String? = null,
    val textSnippet: String,
    val textBefore: String = "",
    val textAfter: String = "",
    val chapterHref: String,
    val spineIndex: Int = 0,
    val progression: Double = 0.0,
    val bookmarkTitle: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val originDeviceId: String,
    val lastModifiedByDeviceId: String,
    val deleted: Boolean = false,
) {
    companion object {
        const val TYPE_HIGHLIGHT = "HIGHLIGHT"
        const val TYPE_BOOKMARK = "BOOKMARK"
        const val COLOR_YELLOW = "yellow"
    }
}
```

- [ ] **Step 2: Add two new DAO methods to `AnnotationDao`**

Add after the existing `updateNote` query:

```kotlin
/** Live, non-deleted annotations for an item sorted by reading position (spine order, then within-chapter). */
@Query(
    "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND deleted = 0 " +
        "ORDER BY spineIndex ASC, progression ASC"
)
fun observeAnnotationsByPosition(serverId: String, itemId: String): Flow<List<AnnotationEntity>>

/** Update the user-editable title of a bookmark, bumping updatedAt + provenance. */
@Query("UPDATE annotations SET bookmarkTitle = :title, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id")
suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String)
```

- [ ] **Step 3: Bump DB version and add `MIGRATION_37_38` in `RiffleDatabase.kt`**

Change the `@Database` annotation version:
```kotlin
version = 38,
```

Add the migration constant after `MIGRATION_36_37`:
```kotlin
val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `annotations` ADD COLUMN `spineIndex` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `annotations` ADD COLUMN `progression` REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE `annotations` ADD COLUMN `bookmarkTitle` TEXT NOT NULL DEFAULT ''")
    }
}
```

- [ ] **Step 4: Register the migration in `DatabaseModule.kt`**

Add `RiffleDatabase.MIGRATION_37_38,` as the last entry in the `addMigrations(…)` call, after `RiffleDatabase.MIGRATION_36_37,`.

- [ ] **Step 5: Build to export schema JSON**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:database:kspDebugKotlin
```

Expected: generates `core/database/schemas/com.riffle.core.database.RiffleDatabase/38.json` with no errors.

- [ ] **Step 6: Write migration test — `migration37To38`**

Add before `migrateFullChain` in `MigrationTest.kt`:

```kotlin
@Test
fun migration37To38_addsPositionAndTitleColumnsToAnnotations() {
    helper.createDatabase(TEST_DB, 37).use { db ->
        db.execSQL(
            "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
        )
        // Pre-existing highlight row with all v37 columns (no spineIndex/progression/bookmarkTitle yet).
        db.execSQL(
            "INSERT INTO annotations (id, serverId, itemId, type, cfi, color, textSnippet, textBefore, textAfter, chapterHref, createdAt, updatedAt, originDeviceId, lastModifiedByDeviceId, deleted) " +
                "VALUES ('a1', 's1', 'book1', 'HIGHLIGHT', 'epubcfi(/6/4!/4/2,/1:0,/1:7)', 'yellow', 'meaning', '', '', 'ch1.xhtml', 1000, 1000, 'dev', 'dev', 0)"
        )
    }

    val db = helper.runMigrationsAndValidate(TEST_DB, 38, true, RiffleDatabase.MIGRATION_37_38)

    // Pre-existing row preserved; new columns default correctly.
    db.query(
        "SELECT id, spineIndex, progression, bookmarkTitle FROM annotations WHERE id = 'a1'"
    ).use { cursor ->
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("a1", cursor.getString(0))
        assertEquals(0, cursor.getInt(1))         // spineIndex DEFAULT 0
        assertEquals(0.0, cursor.getDouble(2), 0.0001) // progression DEFAULT 0.0
        assertEquals("", cursor.getString(3))     // bookmarkTitle DEFAULT ''
    }

    // New bookmark row with populated fields round-trips correctly.
    db.execSQL(
        "INSERT INTO annotations (id, serverId, itemId, type, cfi, color, textSnippet, textBefore, textAfter, chapterHref, spineIndex, progression, bookmarkTitle, createdAt, updatedAt, originDeviceId, lastModifiedByDeviceId, deleted) " +
            "VALUES ('b1', 's1', 'book1', 'BOOKMARK', 'epubcfi(/6/6!/4/1:0)', '', '', '', '', 'ch2.xhtml', 2, 0.42, 'Where it gets weird', 2000, 2000, 'dev', 'dev', 0)"
    )
    db.query("SELECT spineIndex, progression, bookmarkTitle FROM annotations WHERE id = 'b1'").use { cursor ->
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals(2, cursor.getInt(0))
        assertEquals(0.42, cursor.getDouble(1), 0.001)
        assertEquals("Where it gets weird", cursor.getString(2))
    }
}
```

- [ ] **Step 7: Extend `migrateFullChain` to version 38**

In `migrateFullChain`, change the target version and add the new migration:
```kotlin
// Change: val db = helper.runMigrationsAndValidate(TEST_DB, 37, true,
val db = helper.runMigrationsAndValidate(TEST_DB, 38, true,
    // ... existing migrations ...,
    RiffleDatabase.MIGRATION_36_37,
    RiffleDatabase.MIGRATION_37_38,   // add this line
)
```

- [ ] **Step 8: Commit**

```bash
git add core/database/src/main/kotlin/com/riffle/core/database/AnnotationEntity.kt \
        core/database/src/main/kotlin/com/riffle/core/database/AnnotationDao.kt \
        core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt \
        core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt \
        core/database/schemas/ \
        core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt
git commit -m "feat(annotations): DB migration 37→38 — spineIndex, progression, bookmarkTitle"
```

---

## Task 2: Domain model + AnnotationStore

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/Annotation.kt`
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/AnnotationStore.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationStoreImpl.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/AnnotationStoreImplTest.kt`

**Interfaces:**
- Consumes: `AnnotationDao.observeAnnotationsByPosition()`, `AnnotationDao.renameBookmark()` (Task 1)
- Produces: `Annotation.spineIndex: Int`, `Annotation.progression: Double`, `Annotation.bookmarkTitle: String`
- Produces: `AnnotationStore.observeAnnotations(serverId, itemId): Flow<List<Annotation>>`
- Produces: `AnnotationStore.renameBookmark(id: String, title: String)`
- Produces: updated `AnnotationStore.createBookmark(…, spineIndex: Int, progression: Double, bookmarkTitle: String)`

- [ ] **Step 1: Update `Annotation` domain object**

Replace `core/domain/src/main/kotlin/com/riffle/core/domain/Annotation.kt`:

```kotlin
package com.riffle.core.domain

data class Annotation(
    val id: String,
    val serverId: String,
    val itemId: String,
    val type: String,
    val cfi: String,
    val color: String,
    val note: String?,
    val textSnippet: String,
    val textBefore: String,
    val textAfter: String,
    val chapterHref: String,
    val spineIndex: Int,
    val progression: Double,
    val bookmarkTitle: String,
    val createdAt: Long,
    val updatedAt: Long,
)
```

- [ ] **Step 2: Update `AnnotationStore` interface**

Replace `core/domain/src/main/kotlin/com/riffle/core/domain/AnnotationStore.kt`:

```kotlin
package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface AnnotationStore {

    /** Live, non-deleted highlights for an ABS Library Item, oldest first. */
    fun observeHighlights(serverId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted bookmarks for an ABS Library Item, oldest first. */
    fun observeBookmarks(serverId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted highlights + bookmarks for an ABS Library Item, sorted by reading position. */
    fun observeAnnotations(serverId: String, itemId: String): Flow<List<Annotation>>

    suspend fun createHighlight(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        textBefore: String = "",
        textAfter: String = "",
        color: String = DEFAULT_COLOR,
    ): Annotation

    suspend fun createBookmark(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
        bookmarkTitle: String,
    ): Annotation

    suspend fun delete(id: String)
    suspend fun recolor(id: String, color: String)
    suspend fun updateNote(id: String, note: String?)

    /** Update the user-editable title of a bookmark, bumping its updatedAt. */
    suspend fun renameBookmark(id: String, title: String)

    companion object {
        const val DEFAULT_COLOR = "yellow"
    }
}
```

- [ ] **Step 3: Implement in `AnnotationStoreImpl`**

Replace `core/data/src/main/kotlin/com/riffle/core/data/AnnotationStoreImpl.kt`:

```kotlin
package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.DeviceIdStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class AnnotationStoreImpl(
    private val dao: AnnotationDao,
    private val deviceIdStore: DeviceIdStore,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
) : AnnotationStore {

    @Inject
    constructor(dao: AnnotationDao, deviceIdStore: DeviceIdStore) : this(
        dao = dao,
        deviceIdStore = deviceIdStore,
        clock = { System.currentTimeMillis() },
        idGenerator = { UUID.randomUUID().toString() },
    )

    override fun observeHighlights(serverId: String, itemId: String): Flow<List<Annotation>> =
        dao.observeForItem(serverId, itemId).map { rows ->
            rows.filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT }.map { it.toDomain() }
        }

    override fun observeBookmarks(serverId: String, itemId: String): Flow<List<Annotation>> =
        dao.observeForItem(serverId, itemId).map { rows ->
            rows.filter { it.type == AnnotationEntity.TYPE_BOOKMARK }.map { it.toDomain() }
        }

    override fun observeAnnotations(serverId: String, itemId: String): Flow<List<Annotation>> =
        dao.observeAnnotationsByPosition(serverId, itemId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun createHighlight(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        textBefore: String,
        textAfter: String,
        color: String,
    ): Annotation {
        val deviceId = deviceIdStore.getOrCreate()
        val now = clock()
        val entity = AnnotationEntity(
            id = idGenerator(),
            serverId = serverId,
            itemId = itemId,
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = cfi,
            color = color,
            note = null,
            textSnippet = textSnippet,
            textBefore = textBefore,
            textAfter = textAfter,
            chapterHref = chapterHref,
            spineIndex = 0,
            progression = 0.0,
            bookmarkTitle = "",
            createdAt = now,
            updatedAt = now,
            originDeviceId = deviceId,
            lastModifiedByDeviceId = deviceId,
            deleted = false,
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun createBookmark(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
        bookmarkTitle: String,
    ): Annotation {
        val deviceId = deviceIdStore.getOrCreate()
        val now = clock()
        val entity = AnnotationEntity(
            id = idGenerator(),
            serverId = serverId,
            itemId = itemId,
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = cfi,
            color = "",
            note = null,
            textSnippet = textSnippet,
            chapterHref = chapterHref,
            spineIndex = spineIndex,
            progression = progression,
            bookmarkTitle = bookmarkTitle,
            createdAt = now,
            updatedAt = now,
            originDeviceId = deviceId,
            lastModifiedByDeviceId = deviceId,
            deleted = false,
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun delete(id: String) {
        dao.tombstone(id, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }

    override suspend fun recolor(id: String, color: String) {
        dao.recolor(id, color = color, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }

    override suspend fun updateNote(id: String, note: String?) {
        dao.updateNote(id, note = note, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }

    override suspend fun renameBookmark(id: String, title: String) {
        dao.renameBookmark(id, title = title, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }
}

private fun AnnotationEntity.toDomain() = Annotation(
    id = id,
    serverId = serverId,
    itemId = itemId,
    type = type,
    cfi = cfi,
    color = color,
    note = note,
    textSnippet = textSnippet,
    textBefore = textBefore,
    textAfter = textAfter,
    chapterHref = chapterHref,
    spineIndex = spineIndex,
    progression = progression,
    bookmarkTitle = bookmarkTitle,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
```

- [ ] **Step 4: Update `AnnotationStoreImplTest` — add fake DAO methods + new tests**

The fake `AnnotationDao` in the test file is missing `observeAnnotationsByPosition` and `renameBookmark`. Add them to the `dao` object:

```kotlin
override fun observeAnnotationsByPosition(serverId: String, itemId: String): Flow<List<AnnotationEntity>> =
    rows.map { all ->
        all.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }
           .sortedWith(compareBy({ it.spineIndex }, { it.progression }))
    }

override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) {
    rows.value = rows.value.map {
        if (it.id == id) it.copy(bookmarkTitle = title, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
    }
}
```

Also update the `createBookmark` call sites in the existing tests — they must now pass `spineIndex`, `progression`, and `bookmarkTitle`. Search for `createBookmark(` in the test file and add the three new named params (e.g., `spineIndex = 0, progression = 0.0, bookmarkTitle = ""`).

Add these new tests at the end of `AnnotationStoreImplTest`:

```kotlin
@Test
fun `createBookmark stores spineIndex, progression and bookmarkTitle`() = runTest {
    val created = store().createBookmark(
        serverId = "abs1", itemId = "item1",
        cfi = "epubcfi(/6/6!/4/1:0)",
        textSnippet = "", chapterHref = "ch2.xhtml",
        spineIndex = 3, progression = 0.42, bookmarkTitle = "The Egg · 42%",
    )
    assertEquals(3, created.spineIndex)
    assertEquals(0.42, created.progression, 0.001)
    assertEquals("The Egg · 42%", created.bookmarkTitle)
}

@Test
fun `renameBookmark updates the title in the store`() = runTest {
    val s = store()
    val created = s.createBookmark(
        "abs1", "item1", "epubcfi(/6/6!/4/1:0)", "", "ch2.xhtml",
        spineIndex = 0, progression = 0.0, bookmarkTitle = "42%",
    )
    s.renameBookmark(created.id, "Where it gets weird")
    val updated = dao.getById(created.id)
    assertEquals("Where it gets weird", updated?.bookmarkTitle)
}

@Test
fun `observeAnnotations returns highlights and bookmarks sorted by spineIndex then progression`() = runTest {
    val s = store()
    // Insert out-of-order
    s.createHighlight("abs1", "item1", "cfi1", "text", "ch3.xhtml",
        textBefore = "", textAfter = "")
        .also { rows.value = rows.value.map { e -> if (e.id == it.id) e.copy(spineIndex = 2, progression = 0.1) else e } }
    s.createBookmark("abs1", "item1", "cfi2", "", "ch1.xhtml",
        spineIndex = 0, progression = 0.9, bookmarkTitle = "bm1")
    s.createHighlight("abs1", "item1", "cfi3", "text2", "ch1.xhtml",
        textBefore = "", textAfter = "")
        .also { rows.value = rows.value.map { e -> if (e.id == it.id) e.copy(spineIndex = 0, progression = 0.2) else e } }

    val result = store().observeAnnotations("abs1", "item1").first()
    // ch1 progression=0.2, ch1 progression=0.9, ch3 progression=0.1
    assertEquals(3, result.size)
    assertEquals(0, result[0].spineIndex); assertEquals(0.2, result[0].progression, 0.001)
    assertEquals(0, result[1].spineIndex); assertEquals(0.9, result[1].progression, 0.001)
    assertEquals(2, result[2].spineIndex)
}

@Test
fun `observeAnnotations excludes tombstoned annotations`() = runTest {
    val s = store()
    val bm = s.createBookmark("abs1", "item1", "cfi", "", "ch.xhtml",
        spineIndex = 0, progression = 0.0, bookmarkTitle = "x")
    s.delete(bm.id)
    val result = s.observeAnnotations("abs1", "item1").first()
    assertTrue(result.isEmpty())
}
```

- [ ] **Step 5: Run JVM tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test :core:domain:test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/Annotation.kt \
        core/domain/src/main/kotlin/com/riffle/core/domain/AnnotationStore.kt \
        core/data/src/main/kotlin/com/riffle/core/data/AnnotationStoreImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/AnnotationStoreImplTest.kt
git commit -m "feat(annotations): observeAnnotations, renameBookmark, spineIndex/progression/bookmarkTitle"
```

---

## Task 3: EpubBookmarkTitleBuilder

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubBookmarkTitleBuilder.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/EpubBookmarkTitleBuilderTest.kt`

**Interfaces:**
- Consumes: `TocEntry(title: String, href: String, children: List<TocEntry>)` (already in the reader package)
- Produces: `EpubBookmarkTitleBuilder.build(chapterHref, chapterProgression, totalProgression, tocEntries): String`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/riffle/app/feature/reader/EpubBookmarkTitleBuilderTest.kt`:

```kotlin
package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubBookmarkTitleBuilderTest {

    private fun toc(vararg entries: TocEntry) = entries.toList()

    @Test
    fun `named chapter uses title and within-chapter pct`() {
        val entries = toc(
            TocEntry("Prologue", "ch1.xhtml"),
            TocEntry("The Egg", "ch2.xhtml"),
        )
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "ch2.xhtml",
            chapterProgression = 0.34,
            totalProgression = 0.55,
            tocEntries = entries,
        )
        assertEquals("The Egg · 34%", title)
    }

    @Test
    fun `blank chapter title falls back to totalProgression pct`() {
        val entries = toc(TocEntry("   ", "ch1.xhtml"))
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "ch1.xhtml",
            chapterProgression = 0.67,
            totalProgression = 0.45,
            tocEntries = entries,
        )
        assertEquals("45%", title)
    }

    @Test
    fun `no matching TOC entry falls back to totalProgression pct`() {
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "unknown.xhtml",
            chapterProgression = 0.5,
            totalProgression = 0.3,
            tocEntries = emptyList(),
        )
        assertEquals("30%", title)
    }

    @Test
    fun `null totalProgression falls back to chapterProgression pct`() {
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "ch1.xhtml",
            chapterProgression = 0.72,
            totalProgression = null,
            tocEntries = emptyList(),
        )
        assertEquals("72%", title)
    }

    @Test
    fun `chapter title found in nested TOC children`() {
        val entries = toc(
            TocEntry("Part I", "part1.xhtml", children = listOf(
                TocEntry("The Beginning", "ch1.xhtml"),
            )),
        )
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "ch1.xhtml",
            chapterProgression = 0.1,
            totalProgression = null,
            tocEntries = entries,
        )
        assertEquals("The Beginning · 10%", title)
    }

    @Test
    fun `progression rounds correctly at boundary`() {
        val entries = toc(TocEntry("One", "ch.xhtml"))
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "ch.xhtml",
            chapterProgression = 0.999,
            totalProgression = null,
            tocEntries = entries,
        )
        assertEquals("One · 100%", title)
    }
}
```

- [ ] **Step 2: Run tests to confirm failure**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.EpubBookmarkTitleBuilderTest"
```

Expected: compilation failure — `EpubBookmarkTitleBuilder` does not exist.

- [ ] **Step 3: Implement `EpubBookmarkTitleBuilder`**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/EpubBookmarkTitleBuilder.kt`:

```kotlin
package com.riffle.app.feature.reader

import kotlin.math.roundToInt

object EpubBookmarkTitleBuilder {

    fun build(
        chapterHref: String,
        chapterProgression: Double,
        totalProgression: Double?,
        tocEntries: List<TocEntry>,
    ): String {
        val chapterTitle = findTitle(tocEntries, chapterHref)
        if (chapterTitle != null) {
            val pct = (chapterProgression * 100).roundToInt().coerceIn(0, 100)
            return "$chapterTitle · $pct%"
        }
        val fallback = totalProgression ?: chapterProgression
        return "${(fallback * 100).roundToInt().coerceIn(0, 100)}%"
    }

    private fun findTitle(entries: List<TocEntry>, href: String): String? {
        for (entry in entries) {
            if (entry.href == href && entry.title.isNotBlank()) return entry.title
            val child = findTitle(entry.children, href)
            if (child != null) return child
        }
        return null
    }
}
```

- [ ] **Step 4: Run tests to confirm passing**

```bash
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.EpubBookmarkTitleBuilderTest"
```

Expected: 6 tests pass, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubBookmarkTitleBuilder.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/EpubBookmarkTitleBuilderTest.kt
git commit -m "feat(annotations): EpubBookmarkTitleBuilder — chapter+pct or fallback-pct default title"
```

---

## Task 4: ViewModel additions

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

**Interfaces:**
- Consumes: `AnnotationStore.observeAnnotations()`, `AnnotationStore.renameBookmark()`, `AnnotationStore.createBookmark()` with new signature (Tasks 1–2)
- Consumes: `EpubBookmarkTitleBuilder.build()` (Task 3)
- Produces: `annotationsPanelVisible: StateFlow<Boolean>`
- Produces: `annotations: StateFlow<List<Annotation>>`
- Produces: `annotationNavigationEvents: Flow<Locator>`
- Produces: `fun openAnnotationsPanel()`, `fun closeAnnotationsPanel()`
- Produces: `fun navigateToAnnotation(id: String)`
- Produces: `fun renameBookmark(id: String, title: String)`
- Modifies: `toggleBookmark()` — computes `spineIndex`, `progression`, `bookmarkTitle` and passes them to `createBookmark()`

- [ ] **Step 1: Add `annotations` state + `annotationsPanelVisible` + navigation channel**

In `EpubReaderViewModel`, find the block that declares `_tocVisible` (around line 1232). Add the following after it:

```kotlin
private val _annotationsPanelVisible = MutableStateFlow(false)
val annotationsPanelVisible: StateFlow<Boolean> = _annotationsPanelVisible

private val _annotations = MutableStateFlow<List<com.riffle.core.domain.Annotation>>(emptyList())
val annotations: StateFlow<List<com.riffle.core.domain.Annotation>> = _annotations

private val _annotationNavigationChannel = Channel<Locator>(Channel.CONFLATED)
val annotationNavigationEvents: Flow<Locator> = _annotationNavigationChannel.receiveAsFlow()
```

- [ ] **Step 2: Add `openAnnotationsPanel` / `closeAnnotationsPanel`**

Add after `closeToc()`:

```kotlin
fun openAnnotationsPanel() {
    _tocVisible.value = false    // mutual exclusion with TOC
    _annotationsPanelVisible.value = true
}

fun closeAnnotationsPanel() {
    _annotationsPanelVisible.value = false
}
```

Also update `openToc()` to close the annotations panel:
```kotlin
fun openToc() {
    _annotationsPanelVisible.value = false
    _tocVisible.value = true
}
```

- [ ] **Step 3: Add `observeAnnotations` call in the ViewModel**

Find `private fun observeBookmarks(serverId: String)` (around line 1024). Add the following method after it:

```kotlin
private fun observeAnnotationsForPanel(serverId: String) {
    viewModelScope.launch {
        combine(
            annotationStore.observeAnnotations(serverId, itemId),
            state,
        ) { list, st -> list to (st is ReaderState.Ready) }
            .collect { (list, ready) ->
                _annotations.value = if (!ready) emptyList() else list
            }
    }
}
```

Then find the call site where `observeHighlights(activeServer.id)` and `observeBookmarks(activeServer.id)` are called (around line 554–556 in the `Ready` state handling) and add `observeAnnotationsForPanel(activeServer.id)` alongside them.

- [ ] **Step 4: Add `navigateToAnnotation`**

Add after `deleteHighlight`:

```kotlin
fun navigateToAnnotation(id: String) {
    viewModelScope.launch {
        val annotation = _annotations.value.firstOrNull { it.id == id } ?: return@launch
        val locator = cfiStringToLocator(annotation.cfi) ?: return@launch
        _annotationNavigationChannel.trySend(locator)
        _annotationsPanelVisible.value = false
    }
}
```

- [ ] **Step 5: Add `renameBookmark`**

Add after `navigateToAnnotation`:

```kotlin
fun renameBookmark(id: String, title: String) {
    viewModelScope.launch { annotationStore.renameBookmark(id, title) }
}
```

- [ ] **Step 6: Update `toggleBookmark` to pass new fields**

Find `toggleBookmark()` (around line 1096). Replace the `createBookmark(…)` call block with:

```kotlin
val pub = publication ?: return@launch
val href = locator.href.toString()
val spineIdx = pub.readingOrder.indexOfFirst { it.url().toString() == href }.coerceAtLeast(0)
val prog = locator.locations.progression ?: 0.0
val totalProg = locator.locations.totalProgression
val title = EpubBookmarkTitleBuilder.build(
    chapterHref = href,
    chapterProgression = prog,
    totalProgression = totalProg,
    tocEntries = tocEntries.value,
)
val cfi = locator.toPayload().ebookLocation
val snippet = locator.text.before?.take(200).orEmpty()
annotationStore.createBookmark(
    serverId = serverId,
    itemId = itemId,
    cfi = cfi,
    textSnippet = snippet,
    chapterHref = href,
    spineIndex = spineIdx,
    progression = prog,
    bookmarkTitle = title,
)
```

Note: `publication` is the private field holding the open `Publication`; check the exact field name in the ViewModel (look for `private var publication`).

- [ ] **Step 7: Build to check for compilation errors**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin
```

Expected: compiles with no errors.

- [ ] **Step 8: Run all JVM tests**

```bash
./gradlew test
```

Expected: all existing tests pass (no regressions from interface changes).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(annotations): ViewModel — panel state, annotations flow, navigate, rename, title on toggle"
```

---

## Task 5: AnnotationsPanel composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/AnnotationsPanel.kt`

**Interfaces:**
- Consumes: `Annotation` domain object (Task 2) — uses `type`, `color`, `textSnippet`, `note`, `bookmarkTitle`
- Consumes: `AnnotationEntity.TYPE_HIGHLIGHT`, `AnnotationEntity.TYPE_BOOKMARK` constants
- Produces: `AnnotationsPanel(annotations, onNavigate, onDelete, onRename, onDismiss)` composable

- [ ] **Step 1: Create `AnnotationsPanel.kt`**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/AnnotationsPanel.kt`:

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.HighlightColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsPanel(
    annotations: List<Annotation>,
    onNavigate: (id: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onRename: (id: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var renamingId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = "Annotations",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        if (annotations.isEmpty()) {
            Text(
                text = "No annotations yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(annotations, key = { it.id }) { annotation ->
                    AnnotationRow(
                        annotation = annotation,
                        onClick = { onNavigate(annotation.id) },
                        onDelete = { onDelete(annotation.id) },
                        onRename = { renamingId = annotation.id },
                    )
                }
            }
        }
    }

    renamingId?.let { id ->
        val currentTitle = annotations.firstOrNull { it.id == id }?.bookmarkTitle ?: ""
        BookmarkRenameDialog(
            initialTitle = currentTitle,
            onConfirm = { newTitle ->
                onRename(id, newTitle)
                renamingId = null
            },
            onDismiss = { renamingId = null },
        )
    }
}

@Composable
private fun AnnotationRow(
    annotation: Annotation,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (annotation.type == AnnotationEntity.TYPE_BOOKMARK) {
                Icon(
                    Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = HighlightColor.fromToken(annotation.color)?.composeColor
                        ?: MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                ) {}
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            val title = if (annotation.type == AnnotationEntity.TYPE_BOOKMARK) {
                annotation.bookmarkTitle.ifBlank { "Bookmark" }
            } else {
                annotation.textSnippet
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (annotation.type == AnnotationEntity.TYPE_HIGHLIGHT && !annotation.note.isNullOrBlank()) {
                Text(
                    text = annotation.note.take(60),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        AnnotationOverflow(
            isBookmark = annotation.type == AnnotationEntity.TYPE_BOOKMARK,
            onDelete = onDelete,
            onRename = onRename,
        )
    }
}

@Composable
private fun AnnotationOverflow(
    isBookmark: Boolean,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (isBookmark) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onRename()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun BookmarkRenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename bookmark") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.trim()) },
                enabled = title.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
```

Note: `HighlightColor.fromToken(token)?.composeColor` — check whether `HighlightColor` already has a `composeColor` property or an equivalent. If not, map the token string to a `Color` inline:

```kotlin
// Replace the Surface color line with:
color = when (annotation.color) {
    "yellow" -> Color(0xFFF6C627)
    "blue"   -> Color(0xFF4FC3F7)
    "green"  -> Color(0xFF81C784)
    "pink"   -> Color(0xFFF06292)
    else     -> MaterialTheme.colorScheme.primary
},
```

- [ ] **Step 2: Build to check compilation**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin
```

Expected: compiles with no errors. Fix any import or `HighlightColor` API issues revealed.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/AnnotationsPanel.kt
git commit -m "feat(annotations): AnnotationsPanel composable — mixed highlight/bookmark list"
```

---

## Task 6: EpubReaderScreen wiring

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

**Interfaces:**
- Consumes: `viewModel.annotationsPanelVisible`, `viewModel.annotations`, `viewModel.annotationNavigationEvents`, `viewModel.openAnnotationsPanel()`, `viewModel.closeAnnotationsPanel()`, `viewModel.navigateToAnnotation()`, `viewModel.renameBookmark()`, `viewModel.deleteHighlight()` / `viewModel.annotationStore.delete()` (Task 4)
- Consumes: `AnnotationsPanel` composable (Task 5)

- [ ] **Step 1: Collect new ViewModel state**

In `EpubReaderScreen`, find the block where `isCurrentPageBookmarked` is collected (around line 252–253). Add:

```kotlin
val annotationsPanelVisible by viewModel.annotationsPanelVisible.collectAsState()
val annotations by viewModel.annotations.collectAsState()
```

- [ ] **Step 2: Add toolbar icon**

In the `TopAppBar` `actions` block, find the `IconButton` for TOC (`viewModel::openToc`). Add the annotations button **before** it (so it appears between Search and TOC — or after TOC, per spec: "between TOC and Format"):

```kotlin
IconButton(onClick = viewModel::openAnnotationsPanel) {
    Icon(Icons.Filled.Bookmarks, contentDescription = "Annotations")
}
```

Import: `androidx.compose.material.icons.filled.Bookmarks`

Place it **after** the TOC IconButton and **before** the Format ("Aa") IconButton, matching the spec.

- [ ] **Step 3: Add `AnnotationsPanel` to the screen**

Find the `if (tocVisible)` block (around line 394) and add the annotations panel alongside it:

```kotlin
if (annotationsPanelVisible) {
    AnnotationsPanel(
        annotations = annotations,
        onNavigate = { id ->
            viewModel.navigateToAnnotation(id)
        },
        onDelete = { id -> viewModel.deleteAnnotation(id) },
        onRename = { id, title -> viewModel.renameBookmark(id, title) },
        onDismiss = viewModel::closeAnnotationsPanel,
    )
}
```

Note: `deleteAnnotation` is a new convenience function in the ViewModel that calls `annotationStore.delete(id)` and also cleans up `_highlightToEdit` if needed. Add it to the ViewModel:

```kotlin
fun deleteAnnotation(id: String) {
    viewModelScope.launch {
        annotationStore.delete(id)
        if (_highlightToEdit.value == id) _highlightToEdit.value = null
    }
}
```

- [ ] **Step 4: Wire annotation navigation `LaunchedEffect`**

Find where `annotationNavigationEvents` will be consumed. In `EpubNavigatorView` composable call (which is a separate composable that takes navigation flows as parameters), add the new flow. Look at how `searchNavigationEvents` and `returnNavEvents` are passed — `annotationNavigationEvents` follows the same pattern.

In the `EpubNavigatorView` composable definition (search for `fun EpubNavigatorView`), add the new parameter:

```kotlin
annotationNavigationEvents: Flow<Locator>,
```

In the `LaunchedEffect` block that handles `searchNavigationEvents` (around line 1407), add a parallel effect for annotation navigation:

```kotlin
LaunchedEffect(annotationNavigationEvents, isContinuous) {
    annotationNavigationEvents.collect { locator ->
        if (isContinuous) {
            continuousViewRef.value?.navigateTo(
                locator.href.toString(),
                locator.locations.progression?.toFloat() ?: 0f,
            )
        } else {
            goAndSnapWithCover(locator)
        }
    }
}
```

Pass `annotationNavigationEvents = viewModel.annotationNavigationEvents` at the `EpubNavigatorView(…)` call site in `EpubReaderScreen`.

- [ ] **Step 5: Build to check compilation**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin
```

Expected: no errors.

- [ ] **Step 6: Run all JVM tests**

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(annotations): wire annotations panel + toolbar button + navigation to screen"
```

---

## Self-Review Checklist

- [x] **Spec coverage:**
  - Unified list in reading-position order → `observeAnnotationsByPosition` DAO query + `observeAnnotations` store method ✓
  - Tombstoned annotations excluded → `deleted = 0` in query ✓
  - Row identifying detail → `AnnotationRow` shows color dot / bookmark icon, snippet, note subtitle ✓
  - Tap-to-navigate → `navigateToAnnotation` → CFI→Locator → `annotationNavigationEvents` ✓
  - Delete from list → `deleteAnnotation` → soft-delete → store emits without it ✓
  - Tests for list contents/ordering, tombstone exclusion, navigate, rename, delete → Task 2 + 3 ✓
  - Migration test → Task 1 ✓
  - User-editable bookmark titles → `bookmarkTitle` column + `renameBookmark` + `EpubBookmarkTitleBuilder` ✓
  - Toolbar entry point always visible → unconditional `IconButton` in Ready state ✓

- [x] **No placeholders** — all steps have concrete code.

- [x] **Type consistency:**
  - `createBookmark(…, spineIndex: Int, progression: Double, bookmarkTitle: String)` — consistent across Task 2 interface definition, Task 2 impl, Task 4 ViewModel call.
  - `observeAnnotations(serverId, itemId): Flow<List<Annotation>>` — consistent across Task 2 interface, impl, Task 4 ViewModel usage.
  - `renameBookmark(id: String, title: String)` — consistent across Task 2 interface, impl, Task 4 ViewModel delegate, Task 5 panel callback, Task 6 screen call.
  - `deleteAnnotation(id: String)` — defined in Task 6 Step 3, called in Task 6 Step 3 AnnotationsPanel `onDelete`.
