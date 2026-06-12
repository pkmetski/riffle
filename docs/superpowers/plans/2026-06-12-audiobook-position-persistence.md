# Audiobook Position Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the audiobook player a durable local position store that mirrors the ebook reader — a full, durable last-update-wins peer on both the single-peer and matched paths — reusing a shared store base.

**Architecture:** Extract the store contract + timestamp policy currently baked into `ReadingPositionStoreImpl` into a generic `PositionStore<P>` interface and `TimestampedPositionStore<P>` base (Room forbids generic entities/DAOs, so the table layer is mirrored per type). Build a new `audiobook_positions` table + `AudiobookPositionStore` on that base, plus a pure `AudiobookPositionReconciler`. Wire writes into the player's existing `PositionSaveCoordinator` + follow loop, and last-update-wins resume into the open path (and the matched audio-led attach).

**Tech Stack:** Kotlin, Room, Hilt, Media3 (audiobook player), JUnit + kotlinx-coroutines-test.

**Reference spec:** `docs/superpowers/specs/2026-06-12-audiobook-position-persistence-design.md`

**Environment note:** `./gradlew` needs `JAVA_HOME` exported first:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

---

## File structure

- **Shared core (new):** `core/domain/.../PositionStore.kt`, `core/data/.../TimestampedPositionStore.kt`
- **Refactor onto base:** `core/domain/.../ReadingPositionStore.kt`, `core/data/.../ReadingPositionStoreImpl.kt`
- **Audiobook table (new):** `core/database/.../AudiobookPositionEntity.kt`, `core/database/.../AudiobookPositionDao.kt`; edits to `RiffleDatabase.kt`, `DatabaseModule.kt`, exported `33.json`, `MigrationTest.kt`
- **Audiobook store (new):** `core/domain/.../AudiobookPositionStore.kt`, `core/data/.../AudiobookPositionStoreImpl.kt`; bind in `DataModule.kt`; test `AudiobookPositionStoreTest.kt`
- **Reconciler (new):** `core/domain/.../AudiobookPositionReconciler.kt`; test `AudiobookPositionReconcilerTest.kt`
- **Plumbing:** `core/domain/.../AudiobookRepository.kt` (field), `core/data/.../AudiobookRepositoryImpl.kt` (populate); test `AudiobookRepositoryImplTest.kt`
- **Player:** `app/.../audiobook/AudiobookPlayerViewModel.kt`

---

## Task 1: Shared store core + refactor the ebook store onto it

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/PositionStore.kt`
- Create: `core/data/src/main/kotlin/com/riffle/core/data/TimestampedPositionStore.kt`
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/ReadingPositionStore.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ReadingPositionStoreImpl.kt`
- Regression test: `core/data/src/test/kotlin/com/riffle/core/data/ReadingPositionStoreTest.kt` (unchanged — must stay green)

- [ ] **Step 1: Create the generic `PositionStore<P>` interface**

`core/domain/src/main/kotlin/com/riffle/core/domain/PositionStore.kt`:
```kotlin
package com.riffle.core.domain

/**
 * Durable, per-(serverId, itemId) store of a reading/listening position [payload] plus the wall-clock
 * timestamp it was last set at. The timestamp drives last-update-wins reconciliation against the
 * server. Room cannot share generic entities/DAOs, so each medium keeps its own table; this contract
 * and the [TimestampedPositionStore] base are the shared layer.
 */
interface PositionStore<P> {
    suspend fun save(serverId: String, itemId: String, payload: P)
    suspend fun load(serverId: String, itemId: String): P?
    suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long
    suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long)
}
```

- [ ] **Step 2: Create the `TimestampedPositionStore<P>` base**

`core/data/src/main/kotlin/com/riffle/core/data/TimestampedPositionStore.kt`:
```kotlin
package com.riffle.core.data

import com.riffle.core.domain.PositionStore

/**
 * Shared policy for the per-medium position stores: [save] stamps the current wall clock;
 * [loadLocalUpdatedAt] defaults a missing row to 0L. Subclasses supply the per-table storage via the
 * four template methods (Room forbids a shared generic entity/DAO). [now] is overridable for tests.
 */
abstract class TimestampedPositionStore<P> : PositionStore<P> {

    protected open fun now(): Long = System.currentTimeMillis()

    protected abstract suspend fun writePayload(serverId: String, itemId: String, payload: P, updatedAt: Long)
    protected abstract suspend fun readPayload(serverId: String, itemId: String): P?
    protected abstract suspend fun readUpdatedAt(serverId: String, itemId: String): Long?
    protected abstract suspend fun writeUpdatedAt(serverId: String, itemId: String, updatedAt: Long)

    final override suspend fun save(serverId: String, itemId: String, payload: P) =
        writePayload(serverId, itemId, payload, now())

    final override suspend fun load(serverId: String, itemId: String): P? =
        readPayload(serverId, itemId)

    final override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long =
        readUpdatedAt(serverId, itemId) ?: 0L

    final override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) =
        writeUpdatedAt(serverId, itemId, millis)
}
```

- [ ] **Step 3: Make `ReadingPositionStore` extend the generic contract**

Replace the whole body of `core/domain/src/main/kotlin/com/riffle/core/domain/ReadingPositionStore.kt`:
```kotlin
package com.riffle.core.domain

/** The ebook reading position (an EPUB/PDF locator string), stored per (serverId, itemId). */
interface ReadingPositionStore : PositionStore<String>
```

- [ ] **Step 4: Refactor `ReadingPositionStoreImpl` onto the base**

Replace the whole body of `core/data/src/main/kotlin/com/riffle/core/data/ReadingPositionStoreImpl.kt`:
```kotlin
package com.riffle.core.data

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.ReadingPositionStore
import javax.inject.Inject

class ReadingPositionStoreImpl @Inject constructor(
    private val dao: ReadingPositionDao,
) : TimestampedPositionStore<String>(), ReadingPositionStore {

    override suspend fun writePayload(serverId: String, itemId: String, payload: String, updatedAt: Long) {
        dao.upsert(ReadingPositionEntity(serverId, itemId, payload, updatedAt))
    }

    override suspend fun readPayload(serverId: String, itemId: String): String? =
        dao.getByItemId(serverId, itemId)?.cfi

    override suspend fun readUpdatedAt(serverId: String, itemId: String): Long? =
        dao.getByItemId(serverId, itemId)?.localUpdatedAt

    override suspend fun writeUpdatedAt(serverId: String, itemId: String, updatedAt: Long) {
        // upsert (not the UPDATE-only DAO query) so a row is always created — otherwise a stamp before
        // the first position save silently no-ops, leaving localUpdatedAt = 0 and letting the server
        // win every subsequent cycle.
        val existing = dao.getByItemId(serverId, itemId)
        dao.upsert(ReadingPositionEntity(serverId, itemId, existing?.cfi ?: "", updatedAt))
    }
}
```

- [ ] **Step 5: Run the ebook store regression test (must stay green)**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.ReadingPositionStoreTest"
```
Expected: BUILD SUCCESSFUL, all 7 tests pass (proves the refactor preserved behavior).

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/PositionStore.kt \
        core/domain/src/main/kotlin/com/riffle/core/domain/ReadingPositionStore.kt \
        core/data/src/main/kotlin/com/riffle/core/data/TimestampedPositionStore.kt \
        core/data/src/main/kotlin/com/riffle/core/data/ReadingPositionStoreImpl.kt
git commit -m "refactor(position): extract shared PositionStore base from the ebook store"
```

---

## Task 2: Audiobook Room table — entity, DAO, migration, schema, DI

**Files:**
- Create: `core/database/src/main/kotlin/com/riffle/core/database/AudiobookPositionEntity.kt`
- Create: `core/database/src/main/kotlin/com/riffle/core/database/AudiobookPositionDao.kt`
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt` (entities list, version, DAO getter, `MIGRATION_32_33`)
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt` (DAO provider + `addMigrations`)
- Generated: `core/database/schemas/com.riffle.core.database.RiffleDatabase/33.json`

- [ ] **Step 1: Create the entity**

`core/database/src/main/kotlin/com/riffle/core/database/AudiobookPositionEntity.kt`:
```kotlin
package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// The audiobook listen position (book-absolute seconds) + the wall-clock it was last set at, stored
// per (serverId, itemId) — the same identity and FK-cascade as `reading_positions`. Server-synced
// (unlike the device-local `readaloud_resume_positions`): it is a durable last-update-wins peer
// against ABS's media-progress record (ADR 0029).
@Entity(
    tableName = "audiobook_positions",
    primaryKeys = ["serverId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId")],
)
data class AudiobookPositionEntity(
    val serverId: String,
    val itemId: String,
    val positionSec: Double,
    val localUpdatedAt: Long = 0,
)
```

- [ ] **Step 2: Create the DAO**

`core/database/src/main/kotlin/com/riffle/core/database/AudiobookPositionDao.kt`:
```kotlin
package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AudiobookPositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AudiobookPositionEntity)

    @Query("SELECT * FROM audiobook_positions WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun getByItemId(serverId: String, itemId: String): AudiobookPositionEntity?
}
```

- [ ] **Step 3: Register the entity + DAO and bump the version in `RiffleDatabase.kt`**

In the `@Database` `entities = [...]` list, add after `AudioPlaybackPreferencesEntity::class,` (currently line 25):
```kotlin
        AudioPlaybackPreferencesEntity::class,
        AudiobookPositionEntity::class,
```
Change the version (currently line 27):
```kotlin
    version = 33,
```
Add the DAO getter after `audioPlaybackPreferencesDao()` (currently line 44):
```kotlin
    abstract fun audioPlaybackPreferencesDao(): AudioPlaybackPreferencesDao
    abstract fun audiobookPositionDao(): AudiobookPositionDao
```

- [ ] **Step 4: Add `MIGRATION_32_33` in the `companion object`**

In `RiffleDatabase.kt`, immediately after the `MIGRATION_31_32` object (currently ends line 658), inside the `companion object`:
```kotlin
        // Durable local audiobook listen position (ADR 0029): book-absolute seconds + the wall-clock
        // it was last set at, keyed by (serverId, itemId) with serverId FK-cascade — mirrors
        // `reading_positions`. Server-synced (a last-update-wins peer against ABS), unlike the
        // device-local readaloud resume table.
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audiobook_positions` (" +
                        "`serverId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, " +
                        "`positionSec` REAL NOT NULL, " +
                        "`localUpdatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`serverId`, `itemId`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audiobook_positions_serverId` " +
                        "ON `audiobook_positions` (`serverId`)"
                )
            }
        }
```

- [ ] **Step 5: Provide the DAO and register the migration in `DatabaseModule.kt`**

Add the migration to the `addMigrations(...)` call after `RiffleDatabase.MIGRATION_31_32,` (currently line 66):
```kotlin
                RiffleDatabase.MIGRATION_31_32,
                RiffleDatabase.MIGRATION_32_33,
```
Add a DAO provider after `provideAudioPlaybackPreferencesDao` (currently lines 102-104):
```kotlin
    @Provides
    @Singleton
    fun provideAudiobookPositionDao(db: RiffleDatabase): AudiobookPositionDao = db.audiobookPositionDao()
```
Add the import near the other DAO imports at the top of the file:
```kotlin
import com.riffle.core.database.AudiobookPositionDao
```

- [ ] **Step 6: Build to export schema `33.json`**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:database:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL and a new file `core/database/schemas/com.riffle.core.database.RiffleDatabase/33.json` exists. Verify:
```bash
ls core/database/schemas/com.riffle.core.database.RiffleDatabase/33.json
grep -c "audiobook_positions" core/database/schemas/com.riffle.core.database.RiffleDatabase/33.json
```
Expected: the file exists and the grep count is ≥ 1.

- [ ] **Step 7: Commit**

```bash
git add core/database/src/main/kotlin/com/riffle/core/database/AudiobookPositionEntity.kt \
        core/database/src/main/kotlin/com/riffle/core/database/AudiobookPositionDao.kt \
        core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt \
        core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt \
        core/database/schemas/com.riffle.core.database.RiffleDatabase/33.json
git commit -m "feat(database): add audiobook_positions table (migration 32→33)"
```

---

## Task 3: Migration test for 32→33

**Files:**
- Modify: `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`

- [ ] **Step 1: Add `migration32To33_addsAudiobookPositionsTable`**

Insert this `@Test` immediately before `fun migrateFullChain()` (currently line 1352-1353, the `@Test` line above it):
```kotlin
    @Test
    fun migration32To33_addsAudiobookPositionsTable() {
        helper.createDatabase(TEST_DB, 32).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 33, true, RiffleDatabase.MIGRATION_32_33)

        // The new table exists and is empty (no rows are backfilled).
        db.query("SELECT COUNT(*) FROM audiobook_positions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // A position row is writable and reads back with its seconds + timestamp intact.
        db.execSQL(
            "INSERT INTO audiobook_positions (serverId, itemId, positionSec, localUpdatedAt) " +
                "VALUES ('s1', '42', 123.5, 1700000000000)"
        )
        db.query(
            "SELECT positionSec, localUpdatedAt FROM audiobook_positions WHERE serverId = 's1' AND itemId = '42'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(123.5, cursor.getDouble(0), 0.0001)
            assertEquals(1700000000000L, cursor.getLong(1))
        }

        // FK cascade: removing the Server clears its audiobook positions.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 's1'")
        db.query("SELECT COUNT(*) FROM audiobook_positions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }
```

- [ ] **Step 2: Extend `migrateFullChain` to 33**

In `migrateFullChain()`, change the target version in the `runMigrationsAndValidate` call from `32` to `33` (currently line 1361) and add `MIGRATION_32_33` after `MIGRATION_31_32` in that call's argument list (currently line 1392):
```kotlin
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 33, true,
```
```kotlin
            RiffleDatabase.MIGRATION_31_32,
            RiffleDatabase.MIGRATION_32_33,
        )
```

- [ ] **Step 3: Run the migration tests**

```bash
make harness-test
```
(Migration tests are phone-form-factor androidTest; `make harness-test` boots the Harness AVD and runs them.) Expected: `migration32To33_addsAudiobookPositionsTable` and `migrateFullChain` pass.

> Note (from project memory): a handful of *pre-existing* `MigrationTest` steps fail on `main` due to schema-JSON drift unrelated to this change. Confirm the two tests above pass and that you have not introduced any *new* failures; do not chase the known-failing ones here.

- [ ] **Step 4: Commit**

```bash
git add core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt
git commit -m "test(database): migration 32→33 creates audiobook_positions"
```

---

## Task 4: `AudiobookPositionStore` + impl + DI + unit test

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookPositionStore.kt`
- Create: `core/data/src/main/kotlin/com/riffle/core/data/AudiobookPositionStoreImpl.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt` (bind + imports)
- Test: `core/data/src/test/kotlin/com/riffle/core/data/AudiobookPositionStoreTest.kt`

- [ ] **Step 1: Write the failing store test**

`core/data/src/test/kotlin/com/riffle/core/data/AudiobookPositionStoreTest.kt`:
```kotlin
package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudiobookPositionStoreTest {

    private class FakeAudiobookPositionDao : AudiobookPositionDao {
        private val entities: MutableMap<Pair<String, String>, AudiobookPositionEntity> = mutableMapOf()
        val store: Map<Pair<String, String>, AudiobookPositionEntity> get() = entities
        fun seed(entity: AudiobookPositionEntity) { entities[entity.serverId to entity.itemId] = entity }
        override suspend fun upsert(entity: AudiobookPositionEntity) {
            entities[entity.serverId to entity.itemId] = entity
        }
        override suspend fun getByItemId(serverId: String, itemId: String): AudiobookPositionEntity? =
            entities[serverId to itemId]
    }

    @Test
    fun `save persists the seconds for the given item`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao)
        store.save("server-A", "item-1", 123.5)
        assertEquals(123.5, dao.store["server-A" to "item-1"]?.positionSec ?: 0.0, 0.0001)
    }

    @Test
    fun `load returns the saved seconds`() = runTest {
        val dao = FakeAudiobookPositionDao().also {
            it.seed(AudiobookPositionEntity("server-A", "item-1", 42.0, 1L))
        }
        val store = AudiobookPositionStoreImpl(dao)
        assertEquals(42.0, store.load("server-A", "item-1")!!, 0.0001)
    }

    @Test
    fun `load returns null for an item with no saved position`() = runTest {
        val store = AudiobookPositionStoreImpl(FakeAudiobookPositionDao())
        assertNull(store.load("server-A", "item-new"))
    }

    @Test
    fun `save overwrites the previous position for the same server-item`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao)
        store.save("server-A", "item-1", 10.0)
        store.save("server-A", "item-1", 99.0)
        assertEquals(99.0, store.load("server-A", "item-1")!!, 0.0001)
    }

    @Test
    fun `save stamps localUpdatedAt with current time`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao)
        val before = System.currentTimeMillis()
        store.save("server-A", "item-1", 1.0)
        val after = System.currentTimeMillis()
        val ts = dao.store["server-A" to "item-1"]?.localUpdatedAt ?: 0L
        assert(ts in before..after) { "Expected timestamp in [$before..$after] but was $ts" }
    }

    @Test
    fun `loadLocalUpdatedAt defaults to zero for a missing row`() = runTest {
        val store = AudiobookPositionStoreImpl(FakeAudiobookPositionDao())
        assertEquals(0L, store.loadLocalUpdatedAt("server-A", "item-new"))
    }

    @Test
    fun `updateLocalTimestamp creates a row when none exists so it is not silently dropped`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao)
        store.updateLocalTimestamp("server-A", "item-1", 555L)
        assertEquals(555L, store.loadLocalUpdatedAt("server-A", "item-1"))
    }

    @Test
    fun `positions for the same itemId on different servers are isolated`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao)
        store.save("server-A", "item-1", 10.0)
        store.save("server-B", "item-1", 99.0)
        assertEquals(10.0, store.load("server-A", "item-1")!!, 0.0001)
        assertEquals(99.0, store.load("server-B", "item-1")!!, 0.0001)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.AudiobookPositionStoreTest"
```
Expected: FAIL — unresolved reference `AudiobookPositionStoreImpl` (and `AudiobookPositionStore`).

- [ ] **Step 3: Create the domain interface**

`core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookPositionStore.kt`:
```kotlin
package com.riffle.core.domain

/** The audiobook listen position (book-absolute seconds), stored per (serverId, itemId). */
interface AudiobookPositionStore : PositionStore<Double>
```

- [ ] **Step 4: Create the impl on the shared base**

`core/data/src/main/kotlin/com/riffle/core/data/AudiobookPositionStoreImpl.kt`:
```kotlin
package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.domain.AudiobookPositionStore
import javax.inject.Inject

class AudiobookPositionStoreImpl @Inject constructor(
    private val dao: AudiobookPositionDao,
) : TimestampedPositionStore<Double>(), AudiobookPositionStore {

    override suspend fun writePayload(serverId: String, itemId: String, payload: Double, updatedAt: Long) {
        dao.upsert(AudiobookPositionEntity(serverId, itemId, payload, updatedAt))
    }

    override suspend fun readPayload(serverId: String, itemId: String): Double? =
        dao.getByItemId(serverId, itemId)?.positionSec

    override suspend fun readUpdatedAt(serverId: String, itemId: String): Long? =
        dao.getByItemId(serverId, itemId)?.localUpdatedAt

    override suspend fun writeUpdatedAt(serverId: String, itemId: String, updatedAt: Long) {
        // upsert so a stamp before the first save still creates a row (see ReadingPositionStoreImpl).
        val existing = dao.getByItemId(serverId, itemId)
        dao.upsert(AudiobookPositionEntity(serverId, itemId, existing?.positionSec ?: 0.0, updatedAt))
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.AudiobookPositionStoreTest"
```
Expected: PASS (all 8 tests).

- [ ] **Step 6: Bind the store in `DataModule.kt`**

Add the `@Binds` after `bindReadingPositionStore` (currently lines 209-211):
```kotlin
    @Binds
    @Singleton
    abstract fun bindAudiobookPositionStore(impl: AudiobookPositionStoreImpl): AudiobookPositionStore
```
Add imports near the existing ones (`ReadingPositionStoreImpl` import is at line 28; `ReadingPositionStore` at line 57):
```kotlin
import com.riffle.core.data.AudiobookPositionStoreImpl
```
```kotlin
import com.riffle.core.domain.AudiobookPositionStore
```

- [ ] **Step 7: Verify the module compiles (Hilt graph)**

```bash
./gradlew :core:data:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookPositionStore.kt \
        core/data/src/main/kotlin/com/riffle/core/data/AudiobookPositionStoreImpl.kt \
        core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt \
        core/data/src/test/kotlin/com/riffle/core/data/AudiobookPositionStoreTest.kt
git commit -m "feat(audiobook): AudiobookPositionStore backed by the shared position base"
```

---

## Task 5: `AudiobookPositionReconciler` (pure last-update-wins) + test

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookPositionReconciler.kt`
- Test: `core/domain/src/test/kotlin/com/riffle/core/domain/AudiobookPositionReconcilerTest.kt`

- [ ] **Step 1: Write the failing reconciler test**

`core/domain/src/test/kotlin/com/riffle/core/domain/AudiobookPositionReconcilerTest.kt`:
```kotlin
package com.riffle.core.domain

import com.riffle.core.domain.AudiobookPositionReconciler.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookPositionReconcilerTest {

    @Test
    fun `remote newer than local pulls the remote position`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = 10.0, localUpdatedAt = 100L, remoteSec = 50.0, remoteUpdatedAt = 200L,
        )
        assertEquals(Decision.PullRemote(50.0, 200L), d)
    }

    @Test
    fun `local newer than remote pushes the local position`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = 80.0, localUpdatedAt = 300L, remoteSec = 50.0, remoteUpdatedAt = 200L,
        )
        assertEquals(Decision.PushLocal(80.0, 300L), d)
    }

    @Test
    fun `equal timestamps are in sync`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = 80.0, localUpdatedAt = 200L, remoteSec = 50.0, remoteUpdatedAt = 200L,
        )
        assertEquals(Decision.InSync, d)
    }

    @Test
    fun `no local row with a server stamp pulls the remote`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = null, localUpdatedAt = 0L, remoteSec = 50.0, remoteUpdatedAt = 200L,
        )
        assertEquals(Decision.PullRemote(50.0, 200L), d)
    }

    @Test
    fun `no local row and no server stamp is in sync`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = null, localUpdatedAt = 0L, remoteSec = 0.0, remoteUpdatedAt = 0L,
        )
        assertEquals(Decision.InSync, d)
    }

    @Test
    fun `a local stamp of zero never pushes even if remote is also zero`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = 80.0, localUpdatedAt = 0L, remoteSec = 0.0, remoteUpdatedAt = 0L,
        )
        assertEquals(Decision.InSync, d)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:domain:test --tests "com.riffle.core.domain.AudiobookPositionReconcilerTest"
```
Expected: FAIL — unresolved reference `AudiobookPositionReconciler`.

- [ ] **Step 3: Create the reconciler**

`core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookPositionReconciler.kt`:
```kotlin
package com.riffle.core.domain

/**
 * Pure last-update-wins reconciliation for the audiobook's single durable local position against
 * ABS's media-progress record (ADR 0029). Mirrors [StorytellerPositionReconciler] but over absolute
 * seconds: whichever side has the newer timestamp wins; ties stay in sync (local-favoured). The
 * caller resolves the offline case (a missing local row → [localSec] null, [localUpdatedAt] 0).
 */
object AudiobookPositionReconciler {

    sealed interface Decision {
        /** Remote is newer — resume at it and adopt its timestamp into the local row. */
        data class PullRemote(val positionSec: Double, val timestampMillis: Long) : Decision
        /** Local is newer — resume at it (the follow loop / push converges ABS). */
        data class PushLocal(val positionSec: Double, val timestampMillis: Long) : Decision
        data object InSync : Decision
    }

    fun reconcile(
        localSec: Double?,
        localUpdatedAt: Long,
        remoteSec: Double,
        remoteUpdatedAt: Long,
    ): Decision = when {
        remoteUpdatedAt > localUpdatedAt ->
            Decision.PullRemote(remoteSec, remoteUpdatedAt)
        localSec != null && localUpdatedAt > remoteUpdatedAt && localUpdatedAt > 0 ->
            Decision.PushLocal(localSec, localUpdatedAt)
        else -> Decision.InSync
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :core:domain:test --tests "com.riffle.core.domain.AudiobookPositionReconcilerTest"
```
Expected: PASS (all 6 tests).

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookPositionReconciler.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/AudiobookPositionReconcilerTest.kt
git commit -m "feat(audiobook): pure last-update-wins position reconciler"
```

---

## Task 6: Carry ABS `serverLastUpdate` on the session

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookRepository.kt` (add field)
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AudiobookRepositoryImpl.kt` (populate it)
- Test: `core/data/src/test/kotlin/com/riffle/core/data/AudiobookRepositoryImplTest.kt`

- [ ] **Step 1: Add the field to `AudiobookSession`**

In `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookRepository.kt`, change the data class (lines 8-13) to add `serverLastUpdate` with a default so the downloaded-session builder compiles unchanged:
```kotlin
data class AudiobookSession(
    val trackUrls: List<String>,
    val tracks: List<AudiobookTrackSpan>,
    val timeline: AudiobookTimeline,
    val serverCurrentTimeSec: Double,
    // ABS's server-side `lastUpdate` (ms) for this item's media-progress record, for last-update-wins
    // resume against the durable local store. 0 when unknown (offline / downloaded session).
    val serverLastUpdate: Long = 0,
)
```

- [ ] **Step 2: Write the failing repository test**

Add this `@Test` to `core/data/src/test/kotlin/com/riffle/core/data/AudiobookRepositoryImplTest.kt` (after the existing `openSession builds...` test, around line 77). It uses a session API stub that returns a progress record with a `lastUpdate`:
```kotlin
    @Test
    fun `openSession carries the server lastUpdate from the progress record`() = runTest {
        val playback = FakePlaybackApi(
            NetworkPlaybackSessionResult.Success(
                NetworkPlaybackSession(
                    sessionId = "ps",
                    tracks = listOf(NetworkAudioTrack(0, 0.0, 100.0, "/api/items/it/file/1", "audio/mpeg")),
                    chapters = emptyList(),
                    currentTimeSec = 42.0,
                    durationSec = 100.0,
                ),
            ),
        )
        val session = object : AbsSessionApi by NoopSessionApi {
            override suspend fun getProgress(
                baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean,
            ) = NetworkGetProgressResult.Success(
                com.riffle.core.network.NetworkServerProgress(
                    ebookLocation = "", ebookProgress = 0f, currentTime = 42.0, duration = 100.0,
                    lastUpdate = 1700000000000L,
                ),
            )
        }

        val s = repo(playback, session).openSession("srv", "it")!!

        assertEquals(1700000000000L, s.serverLastUpdate)
    }

    @Test
    fun `openSession defaults serverLastUpdate to zero when the progress read fails`() = runTest {
        val playback = FakePlaybackApi(
            NetworkPlaybackSessionResult.Success(
                NetworkPlaybackSession(
                    sessionId = "ps",
                    tracks = listOf(NetworkAudioTrack(0, 0.0, 100.0, "/api/items/it/file/1", "audio/mpeg")),
                    chapters = emptyList(),
                    currentTimeSec = 42.0,
                    durationSec = 100.0,
                ),
            ),
        )
        // NoopSessionApi.getProgress returns NetworkError → no stamp available.
        val s = repo(playback).openSession("srv", "it")!!
        assertEquals(0L, s.serverLastUpdate)
    }
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.AudiobookRepositoryImplTest"
```
Expected: FAIL — `serverLastUpdate` is always 0 (the field isn't populated yet), so the first new test fails on `assertEquals(1700000000000L, ...)`.

- [ ] **Step 4: Populate `serverLastUpdate` in `openSession`**

In `core/data/src/main/kotlin/com/riffle/core/data/AudiobookRepositoryImpl.kt`, add a progress read just before the `return AudiobookSession(...)` (currently line 57), and pass it through. Replace the return block (lines 57-62):
```kotlin
        // Read ABS's server-side lastUpdate so the player can last-update-wins resume against the
        // durable local store. A failed read leaves it 0 (the play-session currentTime still resumes).
        val serverLastUpdate = (
            sessionApi.getProgress(
                baseUrl = server.url.value,
                libraryItemId = itemId,
                token = token,
                insecureAllowed = server.insecureConnectionAllowed,
            ) as? com.riffle.core.network.NetworkGetProgressResult.Success
        )?.progress?.lastUpdate ?: 0L

        return AudiobookSession(
            trackUrls = trackUrls,
            tracks = spans,
            timeline = timeline,
            serverCurrentTimeSec = session.currentTimeSec,
            serverLastUpdate = serverLastUpdate,
        )
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.AudiobookRepositoryImplTest"
```
Expected: PASS — all existing tests plus the two new ones (the existing `openSession builds...` test still passes because `NoopSessionApi.getProgress` returns an error → `serverLastUpdate = 0`, and that test doesn't assert on it).

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookRepository.kt \
        core/data/src/main/kotlin/com/riffle/core/data/AudiobookRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/AudiobookRepositoryImplTest.kt
git commit -m "feat(audiobook): carry ABS server lastUpdate on the opened session"
```

---

## Task 7: Wire the store into the player — write paths

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt`

This task makes the player *persist* the position (hot path on the follow loop + cold path on close). Resume reconciliation is Task 8.

- [ ] **Step 1: Inject `AudiobookPositionStore` into the ViewModel**

In the constructor of `AudiobookPlayerViewModel` (currently ends with `nowPlayingStore` at line 63), add a parameter:
```kotlin
    private val nowPlayingStore: com.riffle.app.playback.NowPlayingStore,
    private val audiobookPositionStore: com.riffle.core.domain.AudiobookPositionStore,
) : ViewModel() {
```

- [ ] **Step 2: Make the coordinator actually persist the position**

Replace the `positionSaveCoordinator` declaration (currently lines 100-107) with one that supplies `savePosition`:
```kotlin
    // Shared local-persistence policy with the ebook reader. Hot path (follow-loop tick): persist the
    // listen position to the durable audiobook store so it survives process death and can win the
    // last-update-wins resume. Cold path (pause/close): also write the `readingProgress` float for the
    // library/detail screens. Backend (ABS) sync runs outside this coordinator (ADR 0029).
    private val positionSaveCoordinator = com.riffle.app.feature.reader.PositionSaveCoordinator(
        updateProgress = { progress -> libraryRepository.updateReadingProgress(itemId, progress) },
        savePosition = { pos -> if (serverId.isNotEmpty()) audiobookPositionStore.save(serverId, itemId, pos) },
    )
```
(The generic type `<Double>` is now inferred from the `savePosition` lambda; keep `PositionSaveCoordinator<Double>(...)` explicit if the compiler complains.)

- [ ] **Step 3: Persist on the follow-loop advancing ticks**

In `startFollowLoop()`, add a hot-path `onChanged(pos)` on the two branches where a genuine forward listen occurs. In the matched advancing branch (currently lines 242-246), after `localUpdatedAt = maxOf(localUpdatedAt, r.canonicalLastUpdate)`:
```kotlin
                    if (playing && pos >= reconciledResumeSec - SETTLE_EPS_SEC) {
                        localUpdatedAt = System.currentTimeMillis()
                        reconciledResumeSec = maxOf(reconciledResumeSec, pos)
                        val r = rs.runAudioLedCycle(pos, localUpdatedAt)
                        localUpdatedAt = maxOf(localUpdatedAt, r.canonicalLastUpdate)
                        positionSaveCoordinator.onChanged(pos)
                    } else {
```
And in the single-peer advancing branch (currently lines 252-257), alongside `saveProgress()`:
```kotlin
                } else if (playing && pos >= reconciledResumeSec - SETTLE_EPS_SEC) {
                    // single-peer: push the ABS audiobook currentTime, but never a transient position
                    // below the resume (which would regress the record).
                    reconciledResumeSec = maxOf(reconciledResumeSec, pos)
                    saveProgress()
                    positionSaveCoordinator.onChanged(pos)
                }
```
(The cold-path `positionSaveCoordinator.onClose(pos, fraction)` in `pushProgressOnStop()` at line 342 is already present and now persists the position too — no change needed there.)

- [ ] **Step 4: Compile the app module**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL (Hilt resolves `AudiobookPositionStore` from the `@Binds` in Task 4).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt
git commit -m "feat(audiobook): persist listen position on the follow loop and close"
```

---

## Task 8: Wire last-update-wins resume + matched-attach durability

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt`

- [ ] **Step 1: Reconcile against the local store on open, before `prepare`**

In `init { ... }`, after `timeline = session.timeline` (currently line 139) and before the speed/`meta` block, insert the reconcile. It computes the resume position and its timestamp:
```kotlin
            timeline = session.timeline
            // Last-update-wins resume against the durable local store (mirrors the ebook reader): if
            // our last listen position is newer than ABS's record — e.g. a final flush was dropped at
            // teardown — resume from it; otherwise adopt the server position and stamp the local row so
            // it does not re-push (ADR 0029).
            val localSec = audiobookPositionStore.load(serverId, itemId)
            val localTs = audiobookPositionStore.loadLocalUpdatedAt(serverId, itemId)
            val resumeSec: Double
            val resumeUpdatedAt: Long
            when (
                val decision = com.riffle.core.domain.AudiobookPositionReconciler.reconcile(
                    localSec = localSec,
                    localUpdatedAt = localTs,
                    remoteSec = session.serverCurrentTimeSec,
                    remoteUpdatedAt = session.serverLastUpdate,
                )
            ) {
                is com.riffle.core.domain.AudiobookPositionReconciler.Decision.PullRemote -> {
                    audiobookPositionStore.save(serverId, itemId, decision.positionSec)
                    audiobookPositionStore.updateLocalTimestamp(serverId, itemId, decision.timestampMillis)
                    resumeSec = decision.positionSec
                    resumeUpdatedAt = decision.timestampMillis
                }
                is com.riffle.core.domain.AudiobookPositionReconciler.Decision.PushLocal -> {
                    resumeSec = decision.positionSec
                    resumeUpdatedAt = decision.timestampMillis
                }
                com.riffle.core.domain.AudiobookPositionReconciler.Decision.InSync -> {
                    resumeSec = session.serverCurrentTimeSec
                    resumeUpdatedAt = session.serverLastUpdate
                }
            }
```

- [ ] **Step 2: Resume the controller at the reconciled position**

Change the `controller.prepare(...)` `startAtSec` argument (currently line 167) from `session.serverCurrentTimeSec` to `resumeSec`:
```kotlin
            controller.prepare(
                trackUrls = session.trackUrls,
                spans = session.tracks,
                durationSec = session.timeline.durationSec,
                startAtSec = resumeSec,
            )
```
And change the `reconciledResumeSec` / seed lines (currently line 175 sets `reconciledResumeSec = session.serverCurrentTimeSec`) to use the reconciled values:
```kotlin
            reconciledResumeSec = resumeSec
            localUpdatedAt = resumeUpdatedAt
```
(`localUpdatedAt` is declared at line 91; seeding it here means a local-won resume can lead the matched cycle.)

- [ ] **Step 3: Give `attachReaderSync` an explicit position + timestamp**

Change the signature of `attachReaderSync` (currently line 204) and its cycle call (line 211) so the open call can pass the reconciled resume (avoiding a transient live-clock read) and let a genuinely-newer local lead:
```kotlin
    private suspend fun attachReaderSync(atSec: Double, atUpdatedAt: Long): Boolean {
        if (readerSync != null || serverId.isEmpty()) return readerSync != null
        val rs = readerSyncFactory.createIfApplicable(itemId) ?: return false
        readerSync = rs
        // Seed the first cycle with the reconciled resume (not the live clock, which may not have
        // settled) and its timestamp: a genuinely-newer local listen leads and propagates to the ebook
        // CFI + audiobook record; a newer remote (e.g. an ABS-web read) still wins and seeks. Passing 0
        // here (the self-heal mid-session attach) keeps it inbound-only so a not-yet-advanced position
        // can never lead.
        val r = rs.runAudioLedCycle(atSec, atUpdatedAt)
        r.jumpToAudioSec?.let { controller.seekTo(it); reconciledResumeSec = it }
        localUpdatedAt = maxOf(localUpdatedAt, r.canonicalLastUpdate)
        return true
    }
```

- [ ] **Step 4: Update the open-time attach call**

In `init`, change the `attachReaderSync()` call (currently line 190) to pass the reconciled resume:
```kotlin
            attachReaderSync(resumeSec, resumeUpdatedAt)
```

- [ ] **Step 5: Update the self-heal attach call (keep it inbound-only)**

In `startFollowLoop()`, change the self-heal call (currently line 230) to pass the live position with timestamp 0 (unchanged behavior — inbound-only):
```kotlin
                if (readerSync == null && attachReaderSync(controller.currentAbsoluteSec(), 0L)) continue
```

- [ ] **Step 6: Compile the app module**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt
git commit -m "feat(audiobook): last-update-wins resume from the local position store"
```

---

## Task 9: Full verification

- [ ] **Step 1: Run the whole unit suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test
```
Expected: BUILD SUCCESSFUL. (Per project memory, `./gradlew test` is required — module-specific `:testDebugUnitTest` misses pure-JVM modules' `:test`.)

- [ ] **Step 2: Run the migration tests on the harness AVD**

```bash
make harness-test
```
Expected: `migration32To33_addsAudiobookPositionsTable` and `migrateFullChain` pass; no *new* failures introduced (see the known-pre-existing-fail note in Task 3).

- [ ] **Step 3: Manual device verification (no VM unit test exists for this player)**

The `AudiobookPlayerViewModel` has 15 constructor dependencies and is not unit-tested in this repo (only the pure `audiobookProgressFraction` helper is). Verify the wiring on a device/emulator:
1. Open an audiobook, listen ~30s, force-stop the app (`adb shell am force-stop com.riffle.app`) — *without* a clean pause — to simulate a dropped final flush.
2. Reopen the audiobook → it resumes at ~30s from the local store (not 0), confirming local-can-win.
3. With the server reachable and a *newer* position set server-side (e.g. via ABS web), reopen → it pulls the server position (remote wins).

- [ ] **Step 4: Final commit (if any verification fixups were needed)**

```bash
git add -A
git commit -m "chore(audiobook): position persistence verification fixups"
```

---

## Self-review notes (addressed)

- **Spec coverage:** Shared base (§1) → Task 1; audiobook table/migration (§2) → Tasks 2-3; store (§3) → Task 4; reconciler (§4) → Task 5; `serverLastUpdate` + write/resume wiring (§5) → Tasks 6-8. All spec sections map to tasks.
- **Type consistency:** `PositionStore<P>` / `TimestampedPositionStore<P>` method names (`save`/`load`/`loadLocalUpdatedAt`/`updateLocalTimestamp`) and the four template methods (`writePayload`/`readPayload`/`readUpdatedAt`/`writeUpdatedAt`) are used identically in Tasks 1 and 4. `AudiobookPositionReconciler.Decision.{PullRemote,PushLocal,InSync}` defined in Task 5 are consumed with matching shapes in Task 8. `AudiobookSession.serverLastUpdate` defined in Task 6 is read in Task 8.
- **Known caveat (benign):** in the matched/synced write path, `save` stamps `now()` while the in-memory `localUpdatedAt` adopts the server stamp; on a later fresh open these differ by ~ms, resolving to the same position. Documented in the spec; not worth special-casing.
