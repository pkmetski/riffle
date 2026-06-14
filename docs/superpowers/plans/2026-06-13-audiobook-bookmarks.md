# Audiobook Bookmarks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a listener mark a spot in an audiobook, give it an editable title, return to it, and have bookmarks sync to Audiobookshelf on the same cadence as audiobook progress.

**Architecture:** New `audiobook_bookmarks` Room table is the local source of truth (DB v34→v35), with dirty-tracking columns mirroring `audiobook_positions` (ADR 0030). Unlike positions (one value per item), bookmarks are a *collection* per item, so sync is a **set reconcile** (push local creates/renames/deletes to ABS's native `/api/me/item/{id}/bookmark` endpoints; pull merges server-side bookmarks), driven by a dedicated reconciler hooked into the existing `ProgressSweep` so it runs at the same frequency as position sync. UI is variant B: a one-tap add icon, a create dialog with a pre-filled `Chapter · offset` title, two labeled pills (Chapters / Bookmarks) each opening a shared `PlayerListSheet`, and bookmark ticks on the scrubber.

**Tech Stack:** Kotlin, Room, Hilt, Kotlinx Serialization, OkHttp, Jetpack Compose, Media3 (existing `AudiobookController`), JUnit/Turbine.

**Spec:** `docs/superpowers/specs/2026-06-13-audiobook-bookmarks-design.md`

---

## Slices

- **Slice 1 (Tasks 1–9):** Local bookmarks, fully working offline — store, domain title builder, ViewModel, create dialog, list sheet, rename/delete, scrubber ticks. No network.
- **Slice 2 (Tasks 10–13):** ABS reconcile (`AbsBookmarkApi` + reconciler + sweep hook) and the offline note.

Each slice produces working, testable software. Slice 1 ships a usable feature; Slice 2 adds sync.

## File Structure

**Slice 1**
- Create `core/database/.../AudiobookBookmarkEntity.kt` — Room entity.
- Create `core/database/.../AudiobookBookmarkDao.kt` — CRUD + dirty queries.
- Modify `core/database/.../RiffleDatabase.kt` — version 35, entity, DAO accessor, `MIGRATION_34_35`.
- Modify `core/data/.../DataModule.kt` — register migration + bindings.
- Create `core/domain/.../AudiobookBookmark.kt` — domain model.
- Create `core/domain/.../BookmarkTitleBuilder.kt` — default-title logic.
- Create `core/domain/.../AudiobookBookmarkStore.kt` — store interface.
- Create `core/data/.../AudiobookBookmarkStoreImpl.kt` — store impl.
- Modify `app/.../audiobook/AudiobookPlayerViewModel.kt` — bookmark state + actions.
- Modify `app/.../audiobook/AudiobookPlayerScreen.kt` — add icon, pills, ticks, wire sheets.
- Create `app/.../audiobook/BookmarkCreateDialog.kt` — title dialog.
- Create `app/.../audiobook/PlayerListSheet.kt` — shared chapters/bookmarks sheet.
- Modify `core/database/src/androidTest/.../MigrationTest.kt` — v34→v35 test + chain.
- Modify `app/.../TestDatabaseModule.kt` (androidTest) — provide the new DAO.

**Slice 2**
- Create `core/network/.../AbsBookmarkApi.kt` — interface + DTOs.
- Modify `core/network/.../AbsApiClient.kt` — implement endpoints.
- Create `core/data/.../AudiobookBookmarkReconciler.kt` — set reconcile.
- Modify the `ProgressSweep` wiring (`core/data/.../ProgressSweep.kt` + its Hilt module) — invoke the reconciler per dirty item.
- Modify `AudiobookPlayerViewModel`/`PlayerListSheet` — offline note.

---

## Slice 1 — Local bookmarks

### Task 1: Bookmark entity + DB migration v34→v35

**Files:**
- Create: `core/database/src/main/kotlin/com/riffle/core/database/AudiobookBookmarkEntity.kt`
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt` (path: wherever `addMigrations(...)` lives)
- Test: `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`

- [ ] **Step 1: Write the entity**

```kotlin
package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// A user bookmark in an audiobook: a titled point (book-absolute seconds) on a library item.
// Unlike audiobook_positions (one value per item) this is a COLLECTION per (serverId, itemId).
// Dirty-tracking + soft-delete mirror ADR 0030: a row is dirty when localUpdatedAt > lastSyncedAt;
// a delete is a tombstone (deleted = 1) kept until the server delete is confirmed, then hard-removed.
@Entity(
    tableName = "audiobook_bookmarks",
    primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId"), Index(value = ["serverId", "itemId"])],
)
data class AudiobookBookmarkEntity(
    val id: String,
    val serverId: String,
    val itemId: String,
    val positionSec: Double,
    val title: String,
    val createdAt: Long,
    val localUpdatedAt: Long = 0,
    val lastSyncedAt: Long = 0,
    val deleted: Boolean = false,
)
```

- [ ] **Step 2: Add the DAO accessor + entity + version bump in `RiffleDatabase.kt`**

Add `AudiobookBookmarkEntity::class` to the `entities = [...]` list, change `version = 34` to `version = 35`, and add `abstract fun audiobookBookmarkDao(): AudiobookBookmarkDao` next to `audiobookPositionDao()`. Add the migration companion object:

```kotlin
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `audiobook_bookmarks` (" +
                "`id` TEXT NOT NULL, " +
                "`serverId` TEXT NOT NULL, " +
                "`itemId` TEXT NOT NULL, " +
                "`positionSec` REAL NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`localUpdatedAt` INTEGER NOT NULL, " +
                "`lastSyncedAt` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_audiobook_bookmarks_serverId` " +
                "ON `audiobook_bookmarks` (`serverId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_audiobook_bookmarks_serverId_itemId` " +
                "ON `audiobook_bookmarks` (`serverId`, `itemId`)"
        )
    }
}
```

- [ ] **Step 3: Register the migration in `DataModule.kt`** — add `RiffleDatabase.MIGRATION_34_35` to the `addMigrations(...)` call.

- [ ] **Step 4: Build so KSP exports the schema**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:database:kspDebugKotlin`
Expected: BUILD SUCCESSFUL, and `core/database/schemas/com.riffle.core.database.RiffleDatabase/35.json` now exists containing `audiobook_bookmarks`.

- [ ] **Step 5: Write the migration test** (append to `MigrationTest.kt`)

```kotlin
@Test
fun migration34To35_addsAudiobookBookmarksTable() {
    helper.createDatabase(TEST_DB, 34).use { db ->
        db.execSQL(
            "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
        )
    }

    val db = helper.runMigrationsAndValidate(TEST_DB, 35, true, RiffleDatabase.MIGRATION_34_35)

    db.query("SELECT COUNT(*) FROM audiobook_bookmarks").use { c ->
        c.moveToFirst(); assertEquals(0, c.getInt(0))
    }

    db.execSQL(
        "INSERT INTO audiobook_bookmarks (id, serverId, itemId, positionSec, title, createdAt, localUpdatedAt, lastSyncedAt, deleted) " +
            "VALUES ('b1', 's1', '42', 765.0, 'The Egg · 12:45', 1700000000000, 1700000000000, 0, 0)"
    )
    db.query(
        "SELECT positionSec, title, createdAt, localUpdatedAt, lastSyncedAt, deleted FROM audiobook_bookmarks WHERE id = 'b1'"
    ).use { c ->
        assertEquals(1, c.count); c.moveToFirst()
        assertEquals(765.0, c.getDouble(0), 0.0001)
        assertEquals("The Egg · 12:45", c.getString(1))
        assertEquals(1700000000000L, c.getLong(2))
        assertEquals(1700000000000L, c.getLong(3))
        assertEquals(0L, c.getLong(4))
        assertEquals(0, c.getInt(5))
    }

    // FK cascade: removing the server clears its bookmarks.
    db.execSQL("PRAGMA foreign_keys = ON")
    db.execSQL("DELETE FROM servers WHERE id = 's1'")
    db.query("SELECT COUNT(*) FROM audiobook_bookmarks").use { c ->
        c.moveToFirst(); assertEquals(0, c.getInt(0))
    }
}
```

Also add `RiffleDatabase.MIGRATION_34_35` to the end of the `migrateFullChain` test's `runMigrationsAndValidate(TEST_DB, 35, true, ...)` call (and change its target version to 35).

- [ ] **Step 6: Run the migration test** (pinned to the self-booted Harness AVD per `reference_harness_macro_only_app_module`)

Run (after booting Harness AVD and setting `ANDROID_SERIAL`):
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:database:connectedDebugAndroidTest --tests "*MigrationTest.migration34To35_addsAudiobookBookmarksTable"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/database core/data
git commit -m "feat(audiobook): audiobook_bookmarks table + migration v34->v35"
```

---

### Task 2: Bookmark DAO

**Files:**
- Create: `core/database/src/main/kotlin/com/riffle/core/database/AudiobookBookmarkDao.kt`
- Test: `core/database/src/androidTest/kotlin/com/riffle/core/database/AudiobookBookmarkDaoTest.kt`

- [ ] **Step 1: Write a failing DAO test**

```kotlin
package com.riffle.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudiobookBookmarkDaoTest {
    private lateinit var db: RiffleDatabase
    private lateinit var dao: AudiobookBookmarkDao

    private fun server(id: String) = ServerEntity(
        id = id, url = "http://x", isActive = true, insecureConnectionAllowed = false,
        username = "u", serverType = "AUDIOBOOKSHELF",
    )

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), RiffleDatabase::class.java,
        ).build()
        dao = db.audiobookBookmarkDao()
    }
    @After fun teardown() = db.close()

    @Test fun observeReturnsNonDeletedOrderedByPosition() = runTest {
        db.serverDao().upsert(server("s1"))
        dao.upsert(AudiobookBookmarkEntity("b2", "s1", "i1", 200.0, "two", 2, 2, 0, false))
        dao.upsert(AudiobookBookmarkEntity("b1", "s1", "i1", 100.0, "one", 1, 1, 0, false))
        dao.upsert(AudiobookBookmarkEntity("bd", "s1", "i1", 50.0, "gone", 3, 3, 0, true))

        val rows = dao.observeForItem("s1", "i1").first()

        assertEquals(listOf("b1", "b2"), rows.map { it.id })
    }

    @Test fun dirtyForServerReturnsDirtyIncludingTombstones() = runTest {
        db.serverDao().upsert(server("s1"))
        dao.upsert(AudiobookBookmarkEntity("clean", "s1", "i1", 10.0, "c", 1, 5, 5, false))
        dao.upsert(AudiobookBookmarkEntity("dirty", "s1", "i1", 20.0, "d", 1, 6, 5, false))
        dao.upsert(AudiobookBookmarkEntity("tomb", "s1", "i1", 30.0, "t", 1, 7, 5, true))

        val dirty = dao.dirtyForServer("s1").map { it.id }.toSet()

        assertEquals(setOf("dirty", "tomb"), dirty)
    }
}
```

(If `serverDao().upsert` differs, match the existing `ServerDao` insert method used in other DAO tests.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests "*AudiobookBookmarkDaoTest"`
Expected: FAIL — `audiobookBookmarkDao` unresolved.

- [ ] **Step 3: Write the DAO**

```kotlin
package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookBookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AudiobookBookmarkEntity)

    /** Live, user-visible bookmarks for an item: non-deleted, earliest position first. */
    @Query(
        "SELECT * FROM audiobook_bookmarks WHERE serverId = :serverId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY positionSec ASC"
    )
    fun observeForItem(serverId: String, itemId: String): Flow<List<AudiobookBookmarkEntity>>

    @Query("SELECT * FROM audiobook_bookmarks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AudiobookBookmarkEntity?

    /** All rows for an item including tombstones (reconcile needs deletes). */
    @Query("SELECT * FROM audiobook_bookmarks WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun allForItem(serverId: String, itemId: String): List<AudiobookBookmarkEntity>

    /** Dirty rows for a server (creates, renames, AND tombstoned deletes). */
    @Query("SELECT * FROM audiobook_bookmarks WHERE serverId = :serverId AND localUpdatedAt > lastSyncedAt")
    suspend fun dirtyForServer(serverId: String): List<AudiobookBookmarkEntity>

    @Query("SELECT DISTINCT serverId FROM audiobook_bookmarks WHERE localUpdatedAt > lastSyncedAt")
    suspend fun serversWithDirtyRows(): List<String>

    /** Mark clean after a successful push, only if untouched since (compare-and-clear, ADR 0030). */
    @Query(
        "UPDATE audiobook_bookmarks SET lastSyncedAt = :serverStamp, localUpdatedAt = :serverStamp " +
            "WHERE id = :id AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Int

    /** Hard-remove a confirmed-deleted tombstone, only if untouched since. */
    @Query("DELETE FROM audiobook_bookmarks WHERE id = :id AND deleted = 1 AND localUpdatedAt = :ifLocalUpdatedAt")
    suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Int

    @Query("DELETE FROM audiobook_bookmarks WHERE id = :id")
    suspend fun hardDelete(id: String)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests "*AudiobookBookmarkDaoTest"`
Expected: PASS.

- [ ] **Step 5: Provide the DAO in the androidTest `TestDatabaseModule`** — add:

```kotlin
@Provides
@Singleton
fun provideAudiobookBookmarkDao(db: RiffleDatabase): AudiobookBookmarkDao = db.audiobookBookmarkDao()
```

- [ ] **Step 6: Commit**

```bash
git add core/database app
git commit -m "feat(audiobook): AudiobookBookmarkDao + test"
```

---

### Task 3: Domain model + default-title builder

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookBookmark.kt`
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/BookmarkTitleBuilder.kt`
- Test: `core/domain/src/test/kotlin/com/riffle/core/domain/BookmarkTitleBuilderTest.kt`

- [ ] **Step 1: Write the domain model**

```kotlin
package com.riffle.core.domain

/** A user bookmark in an audiobook: a titled book-absolute position. */
data class AudiobookBookmark(
    val id: String,
    val positionSec: Double,
    val title: String,
    val createdAt: Long,
)
```

- [ ] **Step 2: Write the failing title-builder test**

```kotlin
package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkTitleBuilderTest {
    private fun timeline(vararg ch: AudiobookChapter) =
        AudiobookTimeline(durationSec = 10_000.0, chapters = ch.toList())

    @Test fun titledChapterUsesTitleAndOffset() {
        val t = timeline(
            AudiobookChapter(0, 0.0, 600.0, "Prologue"),
            AudiobookChapter(1, 600.0, 5000.0, "The Egg"),
        )
        // 600 + 12*60 + 45 = 1365s -> offset into "The Egg" = 765s = 12:45
        assertEquals("The Egg · 12:45", BookmarkTitleBuilder.defaultTitle(t, 1365.0))
    }

    @Test fun untitledChapterFallsBackToChapterNumber() {
        val t = timeline(
            AudiobookChapter(0, 0.0, 600.0, ""),
            AudiobookChapter(1, 600.0, 5000.0, "   "),
        )
        assertEquals("Chapter 2 · 12:45", BookmarkTitleBuilder.defaultTitle(t, 1365.0))
    }

    @Test fun noChaptersFallsBackToAbsoluteTimestamp() {
        val t = AudiobookTimeline(durationSec = 10_000.0, chapters = emptyList())
        // 1:02:11
        assertEquals("1:02:11", BookmarkTitleBuilder.defaultTitle(t, 3731.0))
    }

    @Test fun offsetUnderTenMinutesStillTwoDigitSeconds() {
        val t = timeline(AudiobookChapter(0, 0.0, 5000.0, "One"))
        assertEquals("One · 3:05", BookmarkTitleBuilder.defaultTitle(t, 185.0))
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :core:domain:test --tests "*BookmarkTitleBuilderTest"`
Expected: FAIL — `BookmarkTitleBuilder` unresolved.

- [ ] **Step 4: Write the builder**

```kotlin
package com.riffle.core.domain

/** Builds the pre-filled (editable) default title for a new bookmark from the timeline + position. */
object BookmarkTitleBuilder {

    fun defaultTitle(timeline: AudiobookTimeline, positionSec: Double): String {
        val chapter = timeline.chapterAt(positionSec)
        if (chapter == null) return absolute(positionSec)
        val offset = (positionSec - chapter.startSec).coerceAtLeast(0.0)
        val label = chapter.title.trim().ifEmpty { "Chapter ${chapter.index + 1}" }
        return "$label · ${clock(offset)}"
    }

    // mm:ss when under an hour, h:mm:ss otherwise (offsets into a chapter are usually short).
    private fun clock(sec: Double): String {
        val total = sec.toLong()
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun absolute(sec: Double): String {
        val total = sec.toLong()
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
```

(Confirm `AudiobookTimeline.chapterAt` returns `AudiobookChapter?` and `AudiobookChapter` has `index`, `startSec`, `title` — per the explored domain model. If `chapterAt` is non-null, adjust the null guard accordingly.)

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :core:domain:test --tests "*BookmarkTitleBuilderTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/domain
git commit -m "feat(audiobook): bookmark domain model + default-title builder"
```

---

### Task 4: Bookmark store (interface + impl + bindings)

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookBookmarkStore.kt`
- Create: `core/data/src/main/kotlin/com/riffle/core/data/AudiobookBookmarkStoreImpl.kt`
- Modify: `core/data/.../DataModule.kt` (bindings)
- Test: `core/data/src/test/kotlin/com/riffle/core/data/AudiobookBookmarkStoreImplTest.kt`

- [ ] **Step 1: Write the store interface**

```kotlin
package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/** Local-first CRUD for audiobook bookmarks, scoped per (serverId, itemId). */
interface AudiobookBookmarkStore {
    fun observe(serverId: String, itemId: String): Flow<List<AudiobookBookmark>>

    /** Create a bookmark; returns its generated id. [now] is the wall-clock stamp (createdAt + dirty). */
    suspend fun add(serverId: String, itemId: String, positionSec: Double, title: String, now: Long): String

    suspend fun rename(id: String, title: String, now: Long)

    /** Soft-delete (tombstone) so the deletion can be pushed to ABS, then hard-removed on confirm. */
    suspend fun delete(id: String, now: Long)
}
```

- [ ] **Step 2: Write the failing store test** (uses a fake DAO)

```kotlin
package com.riffle.core.data

import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookBookmarkStoreImplTest {

    // Minimal in-memory fake of the DAO surface the store uses.
    private class FakeDao : AudiobookBookmarkDao {
        val rows = MutableStateFlow<List<AudiobookBookmarkEntity>>(emptyList())
        override suspend fun upsert(entity: AudiobookBookmarkEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }
        override fun observeForItem(serverId: String, itemId: String): Flow<List<AudiobookBookmarkEntity>> =
            rows.map { list -> list.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }.sortedBy { it.positionSec } }
        override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun allForItem(serverId: String, itemId: String) =
            rows.value.filter { it.serverId == serverId && it.itemId == itemId }
        override suspend fun dirtyForServer(serverId: String) =
            rows.value.filter { it.serverId == serverId && it.localUpdatedAt > it.lastSyncedAt }
        override suspend fun serversWithDirtyRows() =
            rows.value.filter { it.localUpdatedAt > it.lastSyncedAt }.map { it.serverId }.distinct()
        override suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long) = 0
        override suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long) = 0
        override suspend fun hardDelete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
    }

    @Test fun addCreatesDirtyRow() = runTest {
        val dao = FakeDao()
        val store = AudiobookBookmarkStoreImpl(dao)

        val id = store.add("s1", "i1", 765.0, "The Egg · 12:45", now = 1000L)

        val row = dao.getById(id)!!
        assertEquals(765.0, row.positionSec, 0.0001)
        assertEquals("The Egg · 12:45", row.title)
        assertEquals(1000L, row.createdAt)
        assertTrue("new row must be dirty", row.localUpdatedAt > row.lastSyncedAt)
        assertEquals(false, row.deleted)
    }

    @Test fun renameBumpsDirtyStamp() = runTest {
        val dao = FakeDao()
        val store = AudiobookBookmarkStoreImpl(dao)
        val id = store.add("s1", "i1", 10.0, "old", now = 1000L)

        store.rename(id, "new", now = 2000L)

        val row = dao.getById(id)!!
        assertEquals("new", row.title)
        assertEquals(2000L, row.localUpdatedAt)
        assertTrue(row.localUpdatedAt > row.lastSyncedAt)
    }

    @Test fun deleteTombstonesNotHardRemoves() = runTest {
        val dao = FakeDao()
        val store = AudiobookBookmarkStoreImpl(dao)
        val id = store.add("s1", "i1", 10.0, "x", now = 1000L)

        store.delete(id, now = 3000L)

        val row = dao.getById(id)!!
        assertEquals(true, row.deleted)
        assertEquals(3000L, row.localUpdatedAt)
        assertTrue("tombstone must be dirty", row.localUpdatedAt > row.lastSyncedAt)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :core:data:test --tests "*AudiobookBookmarkStoreImplTest"`
Expected: FAIL — `AudiobookBookmarkStoreImpl` unresolved.

- [ ] **Step 4: Write the impl**

```kotlin
package com.riffle.core.data

import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.domain.AudiobookBookmark
import com.riffle.core.domain.AudiobookBookmarkStore
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AudiobookBookmarkStoreImpl @Inject constructor(
    private val dao: AudiobookBookmarkDao,
) : AudiobookBookmarkStore {

    override fun observe(serverId: String, itemId: String): Flow<List<AudiobookBookmark>> =
        dao.observeForItem(serverId, itemId).map { rows ->
            rows.map { AudiobookBookmark(it.id, it.positionSec, it.title, it.createdAt) }
        }

    override suspend fun add(serverId: String, itemId: String, positionSec: Double, title: String, now: Long): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = id, serverId = serverId, itemId = itemId, positionSec = positionSec,
                title = title, createdAt = now, localUpdatedAt = now, lastSyncedAt = 0, deleted = false,
            )
        )
        return id
    }

    override suspend fun rename(id: String, title: String, now: Long) {
        val e = dao.getById(id) ?: return
        dao.upsert(e.copy(title = title, localUpdatedAt = now))
    }

    override suspend fun delete(id: String, now: Long) {
        val e = dao.getById(id) ?: return
        dao.upsert(e.copy(deleted = true, localUpdatedAt = now))
    }
}
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :core:data:test --tests "*AudiobookBookmarkStoreImplTest"`
Expected: PASS.

- [ ] **Step 6: Add the Hilt binding** in `DataModule.kt`:

```kotlin
@Binds
@Singleton
abstract fun bindAudiobookBookmarkStore(impl: AudiobookBookmarkStoreImpl): AudiobookBookmarkStore
```

- [ ] **Step 7: Commit**

```bash
git add core/domain core/data
git commit -m "feat(audiobook): AudiobookBookmarkStore (local CRUD) + tests"
```

---

### Task 5: ViewModel wiring

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModelBookmarkTest.kt`

**Context:** Read the existing ViewModel first. It already exposes `AudiobookPlayerUiState` (chapters, current position) and holds `serverId`/`itemId`, the `AudiobookController` (for `seekTo`/`positionSec`), and an `AudiobookTimeline`. Inject `AudiobookBookmarkStore` and a clock (`() -> Long`, default `System::currentTimeMillis`, so tests are deterministic — match how the codebase injects clocks elsewhere; if none, use a `Clock`/lambda parameter).

- [ ] **Step 1: Write a failing ViewModel test** for the default title + add/seek

```kotlin
// Verifies: defaultBookmarkTitle reflects the live position via the timeline;
// addBookmark(title) delegates to the store with the current position and clock;
// bookmarks flow surfaces store emissions in UI state;
// seekToBookmark(positionSec) calls controller.seekTo.
```

Write concrete assertions using fakes for `AudiobookBookmarkStore` and the controller (follow the existing ViewModel test's fakes). Assert:
- `viewModel.defaultBookmarkTitle()` equals `BookmarkTitleBuilder.defaultTitle(timeline, currentPositionSec)`.
- After `viewModel.addBookmark("My title")`, the fake store received `add(serverId, itemId, currentPositionSec, "My title", now=fixedClock)`.
- Emitting two bookmarks from the fake store sets `uiState.value.bookmarks` to the mapped domain list.
- `viewModel.seekToBookmark(123.0)` invoked `controller.seekTo(123.0)`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*AudiobookPlayerViewModelBookmarkTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** — add to the ViewModel:
  - Collect `bookmarkStore.observe(serverId, itemId)` into `AudiobookPlayerUiState.bookmarks: List<AudiobookBookmark>` (add the field, default `emptyList()`).
  - `fun defaultBookmarkTitle(): String = BookmarkTitleBuilder.defaultTitle(timeline, currentPositionSec())`.
  - `fun addBookmark(title: String) { viewModelScope.launch { bookmarkStore.add(serverId, itemId, currentPositionSec(), title, clock()) } }`.
  - `fun renameBookmark(id: String, title: String) { viewModelScope.launch { bookmarkStore.rename(id, title, clock()) } }`.
  - `fun deleteBookmark(id: String) { viewModelScope.launch { bookmarkStore.delete(id, clock()) } }`.
  - `fun seekToBookmark(positionSec: Double) = controller.seekTo(positionSec)` (use the controller's existing absolute-seek method).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*AudiobookPlayerViewModelBookmarkTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "feat(audiobook): bookmark state + add/rename/delete/seek in player VM"
```

---

### Task 6: Create dialog

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/audiobook/BookmarkCreateDialog.kt`

**Context:** Compose `AlertDialog`/`Dialog`. Stateless — takes the pre-filled title and callbacks. Follow existing dialog styling in the app (look for an existing `AlertDialog` usage for theme/buttons).

- [ ] **Step 1: Implement the composable**

```kotlin
@Composable
fun BookmarkCreateDialog(
    initialTitle: String,
    positionLabel: String,          // e.g. "1:02:11 · The Conversation"
    suggestions: List<String>,      // chapter+offset, chapter, absolute, date
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New bookmark") },
        text = {
            Column {
                Text(positionLabel, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.distinct().forEach { s ->
                        SuggestionChip(onClick = { text = s }, label = { Text(s) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim().ifEmpty { initialTitle }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app
git commit -m "feat(audiobook): bookmark create dialog with prefilled title + suggestions"
```

---

### Task 7: Shared list sheet (chapters + bookmarks)

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/audiobook/PlayerListSheet.kt`

**Context:** One reusable `ModalBottomSheet` rendering a titled list. Parameterized by a sealed content type so chapters and bookmarks share the row layout but differ in trailing element. Follow the existing `ModalBottomSheet` usage in the audiobook/reader features for insets + theming.

- [ ] **Step 1: Implement**

```kotlin
sealed interface PlayerListContent {
    data class Chapters(val items: List<AudiobookChapter>, val currentIndex: Int, val onSeek: (AudiobookChapter) -> Unit) : PlayerListContent
    data class Bookmarks(
        val items: List<AudiobookBookmark>,
        val onSeek: (AudiobookBookmark) -> Unit,
        val onRename: (AudiobookBookmark) -> Unit,
        val onDelete: (AudiobookBookmark) -> Unit,
        val offlineNote: Boolean,    // Slice 2; pass false in Slice 1
    ) : PlayerListContent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerListSheet(content: PlayerListContent, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        when (content) {
            is PlayerListContent.Chapters -> {
                Text("Chapters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
                LazyColumn {
                    itemsIndexed(content.items) { i, ch ->
                        PlayerListRow(
                            lead = { if (i == content.currentIndex) NowPlayingIcon() else Text("${i + 1}") },
                            title = ch.title.ifBlank { "Chapter ${i + 1}" },
                            subtitle = if (i == content.currentIndex) "Now playing" else null,
                            trailing = { Text(formatClock(ch.endSec - ch.startSec)) },
                            highlighted = i == content.currentIndex,
                            onClick = { content.onSeek(ch); onDismiss() },
                        )
                    }
                }
            }
            is PlayerListContent.Bookmarks -> {
                Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
                if (content.offlineNote) {
                    Text("Offline — bookmarks will sync", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                if (content.items.isEmpty()) {
                    Text("No bookmarks yet.", modifier = Modifier.padding(20.dp))
                } else {
                    LazyColumn {
                        items(content.items, key = { it.id }) { bm ->
                            PlayerListRow(
                                lead = { PlayIcon() },
                                title = bm.title,
                                subtitle = null,
                                trailing = {
                                    BookmarkOverflowMenu(
                                        onRename = { content.onRename(bm) },
                                        onDelete = { content.onDelete(bm) },
                                    )
                                },
                                highlighted = false,
                                onClick = { content.onSeek(bm); onDismiss() },
                            )
                        }
                    }
                }
            }
        }
    }
}
```

Add the small helpers (`PlayerListRow`, `NowPlayingIcon`, `PlayIcon`, `BookmarkOverflowMenu`, `formatClock`) in the same file, using Material3 + existing icon set. `BookmarkOverflowMenu` is a `DropdownMenu` with "Rename" / "Delete".

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app
git commit -m "feat(audiobook): reusable PlayerListSheet for chapters + bookmarks"
```

---

### Task 8: Wire player screen (add icon, pills, sheets, rename flow)

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerScreen.kt`

**Context:** Read the current screen. Add UI state for which sheet is open (`None | Chapters | Bookmarks`), the create dialog, and a rename dialog (reuse `BookmarkCreateDialog` with the bookmark's current title and an `onConfirm` that calls `renameBookmark`).

- [ ] **Step 1: Add the bookmark-add icon** to the top app-bar row (right side, beside the existing speed control). On click: open the create dialog with `viewModel.defaultBookmarkTitle()` and suggestions (chapter+offset, chapter, absolute, created-date). On confirm: `viewModel.addBookmark(title)` then show a snackbar with **Undo** that calls `viewModel.deleteBookmark(lastId)` (capture the returned id — extend `addBookmark` to return the id via a callback or a `lastCreatedBookmarkId` state).

- [ ] **Step 2: Add the two pills** under the scrubber: "Chapters" → set open sheet = Chapters; "N bookmarks" (count from `uiState.bookmarks.size`) → open sheet = Bookmarks.

- [ ] **Step 3: Render `PlayerListSheet`** based on the open-sheet state, feeding chapters (with `currentIndex` from the timeline) or bookmarks (with seek/rename/delete callbacks; `offlineNote = false` for now). Rename opens the reuse dialog.

- [ ] **Step 4: Verify compile + existing screen tests**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "feat(audiobook): add bookmark icon + chapters/bookmarks pills + sheets on player"
```

---

### Task 9: Scrubber bookmark ticks

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerScreen.kt` (the scrubber composable)

**Context:** The scrubber is a `Slider` (or custom). Overlay tick marks at `positionSec / durationSec` for each bookmark. If it's a Material `Slider`, draw ticks in a `Box` overlaying the track via `Canvas`/`drawBehind`, positioned by fraction. Make each tick tappable to `seekToBookmark`. Keep it lightweight — a thin vertical mark in `onSurface`.

- [ ] **Step 1: Implement the tick overlay**

```kotlin
// Inside the scrubber Box, after the Slider:
Canvas(Modifier.matchParentSize()) {
    val trackTop = size.height / 2 - 7.dp.toPx()
    bookmarks.forEach { bm ->
        val x = (bm.positionSec / durationSec).toFloat().coerceIn(0f, 1f) * size.width
        drawRect(
            color = tickColor,
            topLeft = Offset(x - 1.5.dp.toPx(), trackTop),
            size = Size(3.dp.toPx(), 14.dp.toPx()),
        )
    }
}
```

Add tap handling via `pointerInput` hit-testing the nearest tick within a tolerance (e.g. 12dp) → `seekToBookmark(bm.positionSec)`. (If precise tap-on-tick is fiddly, ship ticks as visual-only in this task and rely on the Bookmarks sheet for jumping; note that decision in the commit.)

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the JVM suites**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`
Expected: PASS (all modules).

- [ ] **Step 4: Commit**

```bash
git add app
git commit -m "feat(audiobook): bookmark ticks on the player scrubber"
```

**Slice 1 complete — local audiobook bookmarks fully usable offline.**

---

## Slice 2 — Audiobookshelf sync

### Task 10: ABS bookmark API

**Files:**
- Create: `core/network/src/main/kotlin/com/riffle/core/network/AbsBookmarkApi.kt`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`
- Test: `core/network/src/test/kotlin/com/riffle/core/network/AbsBookmarkApiTest.kt` (MockWebServer, matching existing network tests)

**Context:** ABS endpoints (verify against the live server `http://media-server:13378`, login `test`/`test`, per `reference_abs_server`): `POST/PATCH /api/me/item/{itemId}/bookmark` with body `{ "time": <seconds:int>, "title": <string> }`; `DELETE /api/me/item/{itemId}/bookmark/{time}`. The user's bookmark list is on `GET /api/me` under a `bookmarks` array of `{ libraryItemId, title, time, createdAt }`. **ABS keys a bookmark by (libraryItemId, time) and has no per-bookmark updatedAt** — so pull-merge is identity-based on `time`, not timestamp-based.

- [ ] **Step 1: Define the interface + DTOs**

```kotlin
package com.riffle.core.network

data class NetworkAbsBookmark(val libraryItemId: String, val title: String, val timeSec: Int, val createdAt: Long)

sealed interface AbsBookmarkResult {
    data class Success(val bookmark: NetworkAbsBookmark) : AbsBookmarkResult
    data class NetworkError(val cause: Throwable) : AbsBookmarkResult
}

sealed interface AbsBookmarkListResult {
    data class Success(val bookmarks: List<NetworkAbsBookmark>) : AbsBookmarkListResult
    data class NetworkError(val cause: Throwable) : AbsBookmarkListResult
}

interface AbsBookmarkApi {
    suspend fun createBookmark(baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean): AbsBookmarkResult
    suspend fun updateBookmark(baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean): AbsBookmarkResult
    suspend fun deleteBookmark(baseUrl: String, itemId: String, timeSec: Int, token: String, insecureAllowed: Boolean): AbsBookmarkResult
    suspend fun listBookmarks(baseUrl: String, token: String, insecureAllowed: Boolean): AbsBookmarkListResult
}
```

- [ ] **Step 2: Write failing MockWebServer tests** for each verb (assert method + path + JSON body + parsed result). Follow the structure of the existing `AbsApiClient`/session tests in `core:network`.

- [ ] **Step 3: Run to verify fail**

Run: `./gradlew :core:network:test --tests "*AbsBookmarkApiTest"`
Expected: FAIL.

- [ ] **Step 4: Implement the four methods on `AbsApiClient`** following the verbatim `syncAudiobookProgress` pattern (OkHttp `Request.Builder`, `Bearer` header, `json.encodeToString(...).toRequestBody(jsonMediaType)`, `trustAllCerts()` when insecure, `withContext(Dispatchers.IO)`, map non-2xx/IOException to `NetworkError`). Add `AbsBookmarkApi` to the `AbsApiClient` class's implemented interfaces. Use `@Serializable` request/response DTOs mirroring `AbsAudiobookProgressRequest`.

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :core:network:test --tests "*AbsBookmarkApiTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/network
git commit -m "feat(network): ABS bookmark endpoints (create/update/delete/list)"
```

---

### Task 11: Bookmark reconciler

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/AudiobookBookmarkReconciler.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/AudiobookBookmarkReconcilerTest.kt`

**Context:** A *set reconcile* per (serverId, itemId), invoked by the sweep. Because ABS has no per-bookmark updatedAt, the policy is **local-intent wins on dirty rows, server fills in the rest**:

- For each **dirty, non-deleted** local row: PATCH if it was previously synced (`lastSyncedAt > 0` ⇒ exists on server) else POST create; on success `confirmPushedIfUnchanged(id, serverStamp, ifLocalUpdatedAt)`.
- For each **dirty tombstone**: DELETE on server (by `time`); on success (or 404) `hardDeleteIfUnchanged(id, ifLocalUpdatedAt)`.
- **Pull:** list server bookmarks for the item; for any server `(time, title)` with **no clean local row at that position**, insert a clean local row (a bookmark made on another device). For a **clean** local row whose `(time)` is absent server-side, hard-delete it (deleted elsewhere). Skip dirty rows in the pull phase (local intent pending).

Identity within an item is `round(positionSec)` == server `time`.

- [ ] **Step 1: Write failing tests** covering: push-create, push-rename, push-delete (tombstone → hard remove), pull-insert (new server bookmark), pull-remove (clean local gone server-side), and "dirty row not clobbered by pull". Use fakes for `AbsBookmarkApi` and the `AudiobookBookmarkDao`/store.

- [ ] **Step 2: Run to verify fail**

Run: `./gradlew :core:data:test --tests "*AudiobookBookmarkReconcilerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `AudiobookBookmarkReconciler`** with a `suspend fun reconcile(serverId, itemId, baseUrl, token, insecureAllowed)` that performs push-then-pull as above, using `dao.allForItem`, `dao.dirtyForServer` (filtered to item), the API, and the compare-and-clear DAO ops. Derive `serverStamp` from `System.currentTimeMillis()` (or an injected clock) since ABS returns `createdAt` only.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:data:test --tests "*AudiobookBookmarkReconcilerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data
git commit -m "feat(audiobook): bookmark set-reconcile against ABS"
```

---

### Task 12: Hook reconciler into the progress sweep

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ProgressSweep.kt` (+ its Hilt provider module)
- Test: `core/data/src/test/kotlin/com/riffle/core/data/ProgressSweepBookmarkTest.kt`

**Context:** The sweep already iterates servers and dirty items. Inject the bookmark DAO (for `serversWithDirtyRows` / dirty items) + the reconciler. Per server, for each item with dirty bookmarks (`dao.dirtyForServer(serverId)` grouped by itemId, plus union with the existing dirty-audio items so bookmarks on a not-otherwise-dirty item still sync), call `bookmarkReconciler.reconcile(...)`. Respect the same `openTargets.isOpen` skip and `locks.withLock` discipline (add a `RemoteKind.ABS_BOOKMARK` lock kind, or reuse `ABS_AUDIO`). This is what makes bookmarks sync **at the same frequency as progress** — same worker, same sweep.

- [ ] **Step 1: Write a failing sweep test** asserting that given a server with a dirty bookmark item, `run()` calls `bookmarkReconciler.reconcile(serverId, itemId, ...)`. Use a fake reconciler.

- [ ] **Step 2: Run to verify fail**

Run: `./gradlew :core:data:test --tests "*ProgressSweepBookmarkTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** the sweep changes + Hilt wiring (provide the reconciler with `AbsBookmarkApi` + DAO; extend `RoomDirtyProgressLedger` or query the bookmark DAO directly for dirty bookmark items).

- [ ] **Step 4: Run to verify pass + full JVM suite**

Run: `./gradlew :core:data:test --tests "*ProgressSweepBookmarkTest"` then `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data
git commit -m "feat(audiobook): sync bookmarks on the progress sweep (same cadence as position)"
```

---

### Task 13: Offline note + final verification

**Files:**
- Modify: `app/.../audiobook/AudiobookPlayerViewModel.kt` (compute `bookmarksOffline`)
- Modify: `app/.../audiobook/AudiobookPlayerScreen.kt` (pass `offlineNote`)

**Context:** Surface the quiet "Offline — bookmarks will sync" note only when there are dirty (unsynced) bookmarks AND the device is offline. Reuse the app's existing connectivity signal (find how other features observe online/offline; if there's a `NetworkMonitor`, inject it). Compute `offlineNote = hasDirtyBookmarks && !isOnline`.

- [ ] **Step 1: Implement** the derived state and pass it into `PlayerListContent.Bookmarks(offlineNote = ...)`.

- [ ] **Step 2: Verify compile + full JVM suite**

Run: `./gradlew :app:compileDebugKotlin && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`
Expected: PASS.

- [ ] **Step 3: Run harness UI smoke (if a player UI test exists)**

Run: `make harness-test`
Expected: PASS (or no new failures vs. the known pre-existing flakes in memory).

- [ ] **Step 4: Commit**

```bash
git add app
git commit -m "feat(audiobook): quiet offline note for unsynced bookmarks"
```

**Slice 2 complete — bookmarks sync to ABS on the progress cadence.**

---

## Self-review notes

- **Spec coverage:** storage table + dirty tracking (T1–T2), default title `Chapter · offset` with fallbacks (T3), local CRUD (T4), ViewModel actions (T5), one-tap add + dialog (T6, T8), shared sheet / two labeled entry points / no tabs (T7–T8), scrubber ticks (T9), ABS native endpoints (T10), set reconcile + last-write-wins-ish given ABS's model (T11), same-cadence sync via the sweep (T12), silent sync + offline note (T13), read-along coexistence (T8 places the add icon in the app-bar row, not the read-along strip). YAGNI items (notes/search/cross-book) intentionally absent.
- **Open question carried from the spec:** ABS bookmark identity is `(libraryItemId, time)` with no per-bookmark updatedAt — handled in T11 by identity-on-`time` merge and local-intent-wins. Verify the exact JSON shape against the live server in T10 before finalizing DTOs.
- **Migration discipline:** T1 follows the `CLAUDE.md` checklist (version bump, schema export, DataModule registration, MigrationTest + chain). Run `MigrationTest` pinned to a self-booted Harness AVD (`reference_harness_macro_only_app_module`).
