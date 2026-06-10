# Per-Book Audio Playback Speed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the Readaloud player's playback speed per book, keyed by a shared "audio identity" so a book's Readaloud and its (future) audiobook share one setting, with a fixed `1×` global default.

**Architecture:** A new device-local Room table `audio_playback_preferences` keyed by `(serverId, bookId)`. An `AudioIdentityResolver` resolves the canonical key: the linked audiobook's ABS id when present, else the Storyteller readaloud id. The Readaloud review repository re-keys the stored row when an audiobook is linked/unlinked. `EpubReaderViewModel` loads the saved speed on open, applies it to the player, and saves on change. See `docs/adr/0028-per-book-audio-playback-settings-keyed-by-audio-identity.md`.

**Tech Stack:** Kotlin, Room, Hilt/Dagger, Media3, JUnit4 + kotlinx-coroutines-test.

**Conventions for every command below:**
- Gradle needs a JDK: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` first (no JDK on PATH).
- Run from repo root `/Users/plamen.kmetski/conductor/workspaces/riffle/denver`.
- JVM unit tests for `core:data` run with `:core:data:testDebugUnitTest`. Final verification uses `./gradlew test` (CI parity — module-specific tasks miss pure-JVM `:test`).

---

## File Structure

**Create:**
- `core/domain/src/main/kotlin/com/riffle/core/domain/AudioIdentity.kt` — the `(serverId, bookId)` key + `AudioPlaybackPreferencesStore` default constant.
- `core/domain/src/main/kotlin/com/riffle/core/domain/AudioPlaybackPreferencesStore.kt` — store interface.
- `core/domain/src/main/kotlin/com/riffle/core/domain/AudioIdentityResolver.kt` — resolver interface.
- `core/database/src/main/kotlin/com/riffle/core/database/AudioPlaybackPreferencesEntity.kt` — Room entity.
- `core/database/src/main/kotlin/com/riffle/core/database/AudioPlaybackPreferencesDao.kt` — Room DAO.
- `core/data/src/main/kotlin/com/riffle/core/data/AudioPlaybackPreferencesStoreImpl.kt` — store impl.
- `core/data/src/main/kotlin/com/riffle/core/data/AudioIdentityResolverImpl.kt` — resolver impl.
- `core/data/src/test/kotlin/com/riffle/core/data/AudioPlaybackPreferencesStoreImplTest.kt`
- `core/data/src/test/kotlin/com/riffle/core/data/AudioIdentityResolverImplTest.kt`

**Modify:**
- `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt` — version 32, register entity + DAO + `MIGRATION_31_32`.
- `core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt` — provide DAO, register migration.
- `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt` — bind store + resolver.
- `core/data/src/main/kotlin/com/riffle/core/data/ReadaloudReviewRepositoryImpl.kt` — re-key on link/unlink.
- `core/data/src/test/kotlin/com/riffle/core/data/ReadaloudReviewRepositoryTest.kt` — re-key tests + helper/fakes.
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` — load/apply/save speed.
- `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt` — migration 31→32 + chain.

---

## Task 1: Domain types

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/AudioIdentity.kt`
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/AudioPlaybackPreferencesStore.kt`
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/AudioIdentityResolver.kt`

These are pure interfaces/value types with no behavior, so there is no separate test step — they are exercised by the impl tests in Tasks 4–7.

- [ ] **Step 1: Create `AudioIdentity.kt`**

```kotlin
package com.riffle.core.domain

/**
 * The key that owns a book's audio playback settings. It is resolved (see [AudioIdentityResolver])
 * to the linked audiobook's `(absServerId, absLibraryItemId)` when one exists, otherwise the
 * Storyteller readaloud's `(storytellerServerId, storytellerBookId)`. `serverId` is always a row in
 * the `servers` table, so Storyteller-rooted and ABS-rooted keys never collide (ADR 0025 / 0028).
 */
data class AudioIdentity(
    val serverId: String,
    val bookId: String,
)
```

- [ ] **Step 2: Create `AudioPlaybackPreferencesStore.kt`**

```kotlin
package com.riffle.core.domain

/**
 * Device-local, per-book audio playback settings (ADR 0028). Keyed by a resolved [AudioIdentity] so
 * a Readaloud and its linked audiobook share one record. A record exists only when the user has
 * overridden the default; absence means the global default. Never synced.
 */
interface AudioPlaybackPreferencesStore {
    /** The saved speed for [identity], or null when the user has not overridden the default. */
    suspend fun load(identity: AudioIdentity): Float?

    /** Persist [speed]; saving the default removes the record (absence == default). */
    suspend fun save(identity: AudioIdentity, speed: Float)

    /** Remove any saved settings for [identity]. */
    suspend fun clear(identity: AudioIdentity)

    /** Move the saved record from [old] to [new] (used when a link/unlink changes the identity). */
    suspend fun rekey(old: AudioIdentity, new: AudioIdentity)

    companion object {
        /** The fixed, non-configurable global default playback speed (ADR 0028). */
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
    }
}
```

- [ ] **Step 3: Create `AudioIdentityResolver.kt`**

```kotlin
package com.riffle.core.domain

/**
 * Resolves the canonical [AudioIdentity] for a readaloud (ADR 0028): the linked audiobook's ABS id
 * when an audiobook is linked, otherwise the Storyteller readaloud id.
 */
interface AudioIdentityResolver {
    suspend fun resolveForStorytellerBook(
        storytellerServerId: String,
        storytellerBookId: String,
    ): AudioIdentity
}
```

- [ ] **Step 4: Build core:domain to verify it compiles**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:domain:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/AudioIdentity.kt \
        core/domain/src/main/kotlin/com/riffle/core/domain/AudioPlaybackPreferencesStore.kt \
        core/domain/src/main/kotlin/com/riffle/core/domain/AudioIdentityResolver.kt
git commit -m "feat(domain): audio playback preferences store + identity resolver interfaces"
```

---

## Task 2: Room entity, DAO, database wiring + migration

**Files:**
- Create: `core/database/src/main/kotlin/com/riffle/core/database/AudioPlaybackPreferencesEntity.kt`
- Create: `core/database/src/main/kotlin/com/riffle/core/database/AudioPlaybackPreferencesDao.kt`
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt`

- [ ] **Step 1: Create `AudioPlaybackPreferencesEntity.kt`**

```kotlin
package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Per-book audio playback settings, device-local and never synced (ADR 0028). Keyed by a resolved
// audio identity (serverId, bookId): the linked audiobook's ABS id when present, else the Storyteller
// readaloud id. serverId FK-cascades so a removed Server's settings are cleared. A row exists only
// when the user has overridden the fixed 1x default. `speed` is nullable to allow the table to grow
// further audio-setting columns later without forcing this one.
@Entity(
    tableName = "audio_playback_preferences",
    primaryKeys = ["serverId", "bookId"],
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
data class AudioPlaybackPreferencesEntity(
    val serverId: String,
    val bookId: String,
    val speed: Float? = null,
)
```

- [ ] **Step 2: Create `AudioPlaybackPreferencesDao.kt`**

```kotlin
package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AudioPlaybackPreferencesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AudioPlaybackPreferencesEntity)

    @Query("SELECT * FROM audio_playback_preferences WHERE serverId = :serverId AND bookId = :bookId LIMIT 1")
    suspend fun get(serverId: String, bookId: String): AudioPlaybackPreferencesEntity?

    @Query("DELETE FROM audio_playback_preferences WHERE serverId = :serverId AND bookId = :bookId")
    suspend fun delete(serverId: String, bookId: String)
}
```

- [ ] **Step 3: Register entity, DAO accessor, version bump, and migration in `RiffleDatabase.kt`**

In the `@Database(entities = [...])` list (currently ending `ReadaloudResumePositionEntity::class,`), add a line:

```kotlin
        ReadaloudResumePositionEntity::class,
        AudioPlaybackPreferencesEntity::class,
    ],
    version = 32,
    exportSchema = true,
)
```

(Change `version = 31` to `version = 32`.)

Add the DAO accessor after `abstract fun readaloudResumePositionDao(): ReadaloudResumePositionDao`:

```kotlin
    abstract fun readaloudResumePositionDao(): ReadaloudResumePositionDao
    abstract fun audioPlaybackPreferencesDao(): AudioPlaybackPreferencesDao
```

Add the migration inside the `companion object`, right after `MIGRATION_30_31` (before the closing brace of the companion object):

```kotlin
        // Per-book audio playback settings (ADR 0028). Device-local, never synced. Keyed by a resolved
        // audio identity (serverId, bookId) — the linked audiobook's ABS id when present, else the
        // Storyteller readaloud id — so a Readaloud and its audiobook share one record. serverId
        // FK-cascades. `speed` is nullable; a row exists only when the user overrides the 1x default.
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audio_playback_preferences` (" +
                        "`serverId` TEXT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`speed` REAL, " +
                        "PRIMARY KEY(`serverId`, `bookId`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audio_playback_preferences_serverId` " +
                        "ON `audio_playback_preferences` (`serverId`)"
                )
            }
        }
```

- [ ] **Step 4: Provide the DAO and register the migration in `DatabaseModule.kt`**

Add to the `.addMigrations(...)` list, after `RiffleDatabase.MIGRATION_30_31,`:

```kotlin
                RiffleDatabase.MIGRATION_30_31,
                RiffleDatabase.MIGRATION_31_32,
            )
```

Add a DAO provider after `provideReadaloudResumePositionDao`:

```kotlin
    @Provides
    @Singleton
    fun provideAudioPlaybackPreferencesDao(db: RiffleDatabase): AudioPlaybackPreferencesDao =
        db.audioPlaybackPreferencesDao()
```

Add the import near the other database imports:

```kotlin
import com.riffle.core.database.AudioPlaybackPreferencesDao
```

- [ ] **Step 5: Build to compile + export the v32 schema JSON (KSP)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:database:kspDebugKotlin :core:data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, and `core/database/schemas/com.riffle.core.database.RiffleDatabase/32.json` now exists.

- [ ] **Step 6: Verify the schema file was generated**

Run: `ls core/database/schemas/com.riffle.core.database.RiffleDatabase/32.json`
Expected: the path prints (file exists).

- [ ] **Step 7: Commit**

```bash
git add core/database/src/main/kotlin/com/riffle/core/database/AudioPlaybackPreferencesEntity.kt \
        core/database/src/main/kotlin/com/riffle/core/database/AudioPlaybackPreferencesDao.kt \
        core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt \
        core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt \
        core/database/schemas/com.riffle.core.database.RiffleDatabase/32.json
git commit -m "feat(database): audio_playback_preferences table + MIGRATION_31_32"
```

---

## Task 3: Migration test

**Files:**
- Modify: `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`

> Room migration tests are connected tests (need an emulator). Per `CLAUDE.md`, never call `./gradlew :app:connectedDebugAndroidTest`. Run this module's connected tests against your own session AVD only (see Task 8). CI never runs connected tests, so this test must still be authored for correctness.

- [ ] **Step 1: Add the `migration31To32` test**

Insert this test immediately before the `migrateFullChain` test (around line 1316):

```kotlin
    @Test
    fun migration31To32_addsAudioPlaybackPreferencesTable() {
        helper.createDatabase(TEST_DB, 31).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 32, true, RiffleDatabase.MIGRATION_31_32)

        // The new table exists and is empty (no rows are backfilled — absence means the 1x default).
        db.query("SELECT COUNT(*) FROM audio_playback_preferences").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // A per-book speed override is writable and reads back.
        db.execSQL(
            "INSERT INTO audio_playback_preferences (serverId, bookId, speed) VALUES ('s1', '42', 1.5)"
        )
        db.query("SELECT speed FROM audio_playback_preferences WHERE serverId = 's1' AND bookId = '42'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(1.5, cursor.getDouble(0), 0.0001)
        }

        // FK cascade: removing the Server clears its audio settings.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 's1'")
        db.query("SELECT COUNT(*) FROM audio_playback_preferences").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }
```

- [ ] **Step 2: Extend `migrateFullChain` to validate through v32**

Change the target version `31` to `32` in the `runMigrationsAndValidate` call:

```kotlin
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 32, true,
```

And append `MIGRATION_31_32` to the vararg list, after `RiffleDatabase.MIGRATION_30_31,`:

```kotlin
            RiffleDatabase.MIGRATION_30_31,
            RiffleDatabase.MIGRATION_31_32,
        )
```

- [ ] **Step 3: Commit**

```bash
git add core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt
git commit -m "test(database): migration 31->32 audio_playback_preferences + full chain"
```

---

## Task 4: Audio playback preferences store impl

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/AudioPlaybackPreferencesStoreImpl.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/AudioPlaybackPreferencesStoreImplTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.riffle.core.data

import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudioPlaybackPreferencesEntity
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioPlaybackPreferencesStore.Companion.DEFAULT_PLAYBACK_SPEED
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPlaybackPreferencesStoreImplTest {

    private val id = AudioIdentity("s1", "42")

    @Test
    fun `save then load returns the speed`() = runTest {
        val store = AudioPlaybackPreferencesStoreImpl(FakeDao())
        store.save(id, 1.5f)
        assertEquals(1.5f, store.load(id))
    }

    @Test
    fun `load is null when nothing saved`() = runTest {
        assertNull(AudioPlaybackPreferencesStoreImpl(FakeDao()).load(id))
    }

    @Test
    fun `saving the default removes the record`() = runTest {
        val store = AudioPlaybackPreferencesStoreImpl(FakeDao())
        store.save(id, 1.5f)
        store.save(id, DEFAULT_PLAYBACK_SPEED)
        assertNull("default speed must not persist a row", store.load(id))
    }

    @Test
    fun `clear removes the record`() = runTest {
        val store = AudioPlaybackPreferencesStoreImpl(FakeDao())
        store.save(id, 2f)
        store.clear(id)
        assertNull(store.load(id))
    }

    @Test
    fun `rekey moves the saved speed to the new identity`() = runTest {
        val store = AudioPlaybackPreferencesStoreImpl(FakeDao())
        store.save(id, 1.25f)
        val newId = AudioIdentity("s2", "audio")
        store.rekey(id, newId)
        assertNull(store.load(id))
        assertEquals(1.25f, store.load(newId))
    }

    @Test
    fun `rekey is a no-op when there is nothing at the old identity`() = runTest {
        val store = AudioPlaybackPreferencesStoreImpl(FakeDao())
        store.rekey(id, AudioIdentity("s2", "audio"))
        assertNull(store.load(AudioIdentity("s2", "audio")))
    }

    private class FakeDao : AudioPlaybackPreferencesDao {
        private val rows = mutableMapOf<Pair<String, String>, AudioPlaybackPreferencesEntity>()
        override suspend fun upsert(entity: AudioPlaybackPreferencesEntity) {
            rows[entity.serverId to entity.bookId] = entity
        }
        override suspend fun get(serverId: String, bookId: String) = rows[serverId to bookId]
        override suspend fun delete(serverId: String, bookId: String) { rows.remove(serverId to bookId) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (impl not written)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:data:testDebugUnitTest --tests '*AudioPlaybackPreferencesStoreImplTest*'`
Expected: FAIL to compile — `AudioPlaybackPreferencesStoreImpl` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.riffle.core.data

import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudioPlaybackPreferencesEntity
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.AudioPlaybackPreferencesStore.Companion.DEFAULT_PLAYBACK_SPEED
import javax.inject.Inject

/**
 * Persists per-book audio playback settings keyed by the resolved [AudioIdentity] (ADR 0028). Unlike
 * the formatting store, the identity already carries (serverId, bookId), so no active-server lookup
 * is needed. Saving the default removes the row, keeping "no row == default".
 */
class AudioPlaybackPreferencesStoreImpl @Inject constructor(
    private val dao: AudioPlaybackPreferencesDao,
) : AudioPlaybackPreferencesStore {

    override suspend fun load(identity: AudioIdentity): Float? =
        dao.get(identity.serverId, identity.bookId)?.speed

    override suspend fun save(identity: AudioIdentity, speed: Float) {
        if (speed == DEFAULT_PLAYBACK_SPEED) {
            dao.delete(identity.serverId, identity.bookId)
            return
        }
        dao.upsert(AudioPlaybackPreferencesEntity(identity.serverId, identity.bookId, speed))
    }

    override suspend fun clear(identity: AudioIdentity) {
        dao.delete(identity.serverId, identity.bookId)
    }

    override suspend fun rekey(old: AudioIdentity, new: AudioIdentity) {
        if (old == new) return
        val existing = dao.get(old.serverId, old.bookId) ?: return
        dao.delete(old.serverId, old.bookId)
        dao.upsert(existing.copy(serverId = new.serverId, bookId = new.bookId))
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:data:testDebugUnitTest --tests '*AudioPlaybackPreferencesStoreImplTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/AudioPlaybackPreferencesStoreImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/AudioPlaybackPreferencesStoreImplTest.kt
git commit -m "feat(data): AudioPlaybackPreferencesStoreImpl with default-removes-row + rekey"
```

---

## Task 5: Audio identity resolver impl

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/AudioIdentityResolverImpl.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/AudioIdentityResolverImplTest.kt`

- [ ] **Step 1: Write the failing test**

This test reuses `ThrowingLibraryItemDao` (from `NoopLibraryDaos.kt`) via Kotlin interface delegation, overriding only `getById`.

```kotlin
package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.domain.AudioIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioIdentityResolverImplTest {

    @Test
    fun `prefers the linked audiobook's id`() = runTest {
        val links = FakeLinkDao().apply {
            seed(link("abs-1", "ebook", "st-1", "42"))
            seed(link("abs-1", "audio", "st-1", "42"))
        }
        val items = FakeLibraryItemDao().apply {
            seed(item("abs-1", "ebook", hasAudio = false))
            seed(item("abs-1", "audio", hasAudio = true))
        }
        val resolver = AudioIdentityResolverImpl(links, items)

        assertEquals(AudioIdentity("abs-1", "audio"), resolver.resolveForStorytellerBook("st-1", "42"))
    }

    @Test
    fun `falls back to the Storyteller id when only an ebook is linked`() = runTest {
        val links = FakeLinkDao().apply { seed(link("abs-1", "ebook", "st-1", "42")) }
        val items = FakeLibraryItemDao().apply { seed(item("abs-1", "ebook", hasAudio = false)) }
        val resolver = AudioIdentityResolverImpl(links, items)

        assertEquals(AudioIdentity("st-1", "42"), resolver.resolveForStorytellerBook("st-1", "42"))
    }

    @Test
    fun `falls back to the Storyteller id when nothing is linked`() = runTest {
        val resolver = AudioIdentityResolverImpl(FakeLinkDao(), FakeLibraryItemDao())
        assertEquals(AudioIdentity("st-1", "42"), resolver.resolveForStorytellerBook("st-1", "42"))
    }

    private fun link(absServerId: String, absItemId: String, stServerId: String, stBookId: String) =
        ReadaloudLinkEntity(absServerId, absItemId, stServerId, stBookId, ReadaloudLinkEntity.STATE_CONFIRMED, true, 1L, 1L)

    private fun item(serverId: String, id: String, hasAudio: Boolean) =
        LibraryItemEntity(serverId, id, "lib", "Title", "Author", null, 0f, hasAudio = hasAudio)

    private class FakeLinkDao : ReadaloudLinkDao {
        private val store = mutableMapOf<Pair<String, String>, ReadaloudLinkEntity>()
        fun seed(e: ReadaloudLinkEntity) { store[e.absServerId to e.absLibraryItemId] = e }
        override suspend fun upsert(entity: ReadaloudLinkEntity) { store[entity.absServerId to entity.absLibraryItemId] = entity }
        override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String) = store[absServerId to absLibraryItemId]
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String) =
            store.values.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        override fun observeAll(): Flow<List<ReadaloudLinkEntity>> = flowOf(store.values.toList())
        override suspend fun allRows() = store.values.toList()
        override fun observeLinkedAbsItemIds(): Flow<List<String>> = flowOf(store.values.map { it.absLibraryItemId })
        override suspend fun countForServer(serverId: String) = 0
        override suspend fun deleteByAbsItem(absServerId: String, absLibraryItemId: String) { store.remove(absServerId to absLibraryItemId) }
        override suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String) {
            store.values.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
                .forEach { store.remove(it.absServerId to it.absLibraryItemId) }
        }
    }

    private class FakeLibraryItemDao : LibraryItemDao by ThrowingLibraryItemDao {
        private val items = mutableMapOf<Pair<String, String>, LibraryItemEntity>()
        fun seed(e: LibraryItemEntity) { items[e.serverId to e.id] = e }
        override suspend fun getById(serverId: String, itemId: String) = items[serverId to itemId]
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (impl not written)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:data:testDebugUnitTest --tests '*AudioIdentityResolverImplTest*'`
Expected: FAIL to compile — `AudioIdentityResolverImpl` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import javax.inject.Inject

/**
 * Resolves the canonical audio-settings key (ADR 0028): if any ABS item linked to the readaloud
 * carries audio (`hasAudio`), that audiobook's id owns the settings; otherwise the Storyteller
 * readaloud id does. The cardinality is 0–1 audiobook per readaloud; the sort keeps the key stable
 * if the data is ever dirty.
 */
class AudioIdentityResolverImpl @Inject constructor(
    private val linkDao: ReadaloudLinkDao,
    private val libraryItemDao: LibraryItemDao,
) : AudioIdentityResolver {

    override suspend fun resolveForStorytellerBook(
        storytellerServerId: String,
        storytellerBookId: String,
    ): AudioIdentity {
        val audiobook = linkDao.findByStorytellerBook(storytellerServerId, storytellerBookId)
            .sortedBy { it.absLibraryItemId }
            .firstOrNull { libraryItemDao.getById(it.absServerId, it.absLibraryItemId)?.hasAudio == true }
        return if (audiobook != null) {
            AudioIdentity(audiobook.absServerId, audiobook.absLibraryItemId)
        } else {
            AudioIdentity(storytellerServerId, storytellerBookId)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:data:testDebugUnitTest --tests '*AudioIdentityResolverImplTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/AudioIdentityResolverImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/AudioIdentityResolverImplTest.kt
git commit -m "feat(data): AudioIdentityResolverImpl prefers linked audiobook id"
```

---

## Task 6: Hilt bindings for the store and resolver

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`

- [ ] **Step 1: Add imports**

Near the other `com.riffle.core.data.*` impl imports add:

```kotlin
import com.riffle.core.data.AudioIdentityResolverImpl
import com.riffle.core.data.AudioPlaybackPreferencesStoreImpl
```

Near the other `com.riffle.core.domain.*` interface imports add:

```kotlin
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
```

- [ ] **Step 2: Add the `@Binds` methods**

Inside `abstract class DataModule`, after `bindBookFormattingPreferencesStore`:

```kotlin
    @Binds
    @Singleton
    abstract fun bindAudioPlaybackPreferencesStore(impl: AudioPlaybackPreferencesStoreImpl): AudioPlaybackPreferencesStore

    @Binds
    @Singleton
    abstract fun bindAudioIdentityResolver(impl: AudioIdentityResolverImpl): AudioIdentityResolver
```

- [ ] **Step 3: Compile to verify the Hilt graph resolves**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt
git commit -m "feat(di): bind AudioPlaybackPreferencesStore + AudioIdentityResolver"
```

---

## Task 7: Re-key the saved record on link/unlink

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ReadaloudReviewRepositoryImpl.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/ReadaloudReviewRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Add these three tests inside `ReadaloudReviewRepositoryTest` (after the existing `unlinkAbsItem` test):

```kotlin
    @Test
    fun `linking an audiobook migrates the saved speed onto the audiobook id`() = runTest {
        // Speed was set during readaloud (no audiobook yet) → keyed by the Storyteller id.
        val links = RecordingLinkDao()
        val items = RecordingLibraryItemDao().apply { seed(audiobook("abs-1", "audio")) }
        val audio = FakeAudioPlaybackPreferencesStore().apply {
            store[AudioIdentity("st-1", "42")] = 1.5f
        }
        val repo = repo(links, RecordingCandidateDao(), libraryItemDao = items, audioPrefs = audio)

        repo.confirmCandidate("st-1", "42", "abs-1", "audio")

        assertNull(audio.store[AudioIdentity("st-1", "42")])
        assertEquals(1.5f, audio.store[AudioIdentity("abs-1", "audio")])
    }

    @Test
    fun `unlinking the audiobook migrates the speed back to the readaloud id`() = runTest {
        val links = RecordingLinkDao().apply { seed(link("abs-1", "audio", "st-1", "42", userConfirmed = true)) }
        val items = RecordingLibraryItemDao().apply { seed(audiobook("abs-1", "audio")) }
        val audio = FakeAudioPlaybackPreferencesStore().apply {
            store[AudioIdentity("abs-1", "audio")] = 2f
        }
        val repo = repo(links, RecordingCandidateDao(), libraryItemDao = items, audioPrefs = audio)

        repo.unlinkBook("st-1", "42")

        assertNull(audio.store[AudioIdentity("abs-1", "audio")])
        assertEquals(2f, audio.store[AudioIdentity("st-1", "42")])
    }

    @Test
    fun `linking an ebook does not move the speed`() = runTest {
        val links = RecordingLinkDao()
        val items = RecordingLibraryItemDao().apply { seed(ebook("abs-1", "ebook")) }
        val audio = FakeAudioPlaybackPreferencesStore().apply {
            store[AudioIdentity("st-1", "42")] = 1.25f
        }
        val repo = repo(links, RecordingCandidateDao(), libraryItemDao = items, audioPrefs = audio)

        repo.confirmCandidate("st-1", "42", "abs-1", "ebook")

        assertEquals(1.25f, audio.store[AudioIdentity("st-1", "42")])
    }
```

Add these helpers + fakes inside the test class (place near the other `private fun`/`private class` members):

```kotlin
    private fun audiobook(serverId: String, id: String) =
        LibraryItemEntity(serverId, id, "lib", "Title", "Author", null, 0f, hasAudio = true)

    private fun ebook(serverId: String, id: String) =
        LibraryItemEntity(serverId, id, "lib", "Title", "Author", null, 0f, hasAudio = false)

    private class RecordingLibraryItemDao : LibraryItemDao by ThrowingLibraryItemDao {
        private val items = mutableMapOf<Pair<String, String>, LibraryItemEntity>()
        fun seed(e: LibraryItemEntity) { items[e.serverId to e.id] = e }
        override suspend fun getById(serverId: String, itemId: String) = items[serverId to itemId]
    }

    private class FakeAudioPlaybackPreferencesStore : AudioPlaybackPreferencesStore {
        val store = mutableMapOf<AudioIdentity, Float>()
        override suspend fun load(identity: AudioIdentity) = store[identity]
        override suspend fun save(identity: AudioIdentity, speed: Float) {
            if (speed == AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED) store.remove(identity) else store[identity] = speed
        }
        override suspend fun clear(identity: AudioIdentity) { store.remove(identity) }
        override suspend fun rekey(old: AudioIdentity, new: AudioIdentity) {
            val v = store.remove(old) ?: return
            store[if (old == new) old else new] = v
        }
    }
```

Update the `repo(...)` helper to wire the new dependencies (the real resolver, built from the test's link + library-item DAOs, so before/after resolution is genuinely integration-tested):

```kotlin
    private fun repo(
        links: RecordingLinkDao,
        candidates: RecordingCandidateDao,
        dismissals: RecordingDismissalDao = RecordingDismissalDao(),
        libraryItemDao: LibraryItemDao = ThrowingLibraryItemDao,
        audioPrefs: FakeAudioPlaybackPreferencesStore = FakeAudioPlaybackPreferencesStore(),
    ) = ReadaloudReviewRepositoryImpl(
        libraryItemDao = libraryItemDao,
        libraryDao = ThrowingLibraryDao,
        linkDao = links,
        candidateDao = candidates,
        dismissalDao = dismissals,
        audioIdentityResolver = AudioIdentityResolverImpl(links, libraryItemDao),
        audioPlaybackPreferencesStore = audioPrefs,
        clock = { 1000L },
    )
```

Add the imports at the top of the test file:

```kotlin
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioPlaybackPreferencesStore
```

- [ ] **Step 2: Run the tests to verify they fail (constructor params don't exist yet)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:data:testDebugUnitTest --tests '*ReadaloudReviewRepositoryTest*'`
Expected: FAIL to compile — `ReadaloudReviewRepositoryImpl` has no `audioIdentityResolver` / `audioPlaybackPreferencesStore` params.

- [ ] **Step 3: Add the dependencies + re-keying to `ReadaloudReviewRepositoryImpl`**

Add imports near the existing `com.riffle.core.domain.*` imports:

```kotlin
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
```

Change the primary constructor to add the two dependencies before `clock`, and update the `@Inject` constructor to pass them through:

```kotlin
@Singleton
class ReadaloudReviewRepositoryImpl(
    private val libraryItemDao: LibraryItemDao,
    private val libraryDao: LibraryDao,
    private val linkDao: ReadaloudLinkDao,
    private val candidateDao: ReadaloudCandidateDao,
    private val dismissalDao: ReadaloudDismissalDao,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    private val clock: () -> Long,
) : ReadaloudReviewRepository {

    @Inject constructor(
        libraryItemDao: LibraryItemDao,
        libraryDao: LibraryDao,
        linkDao: ReadaloudLinkDao,
        candidateDao: ReadaloudCandidateDao,
        dismissalDao: ReadaloudDismissalDao,
        audioIdentityResolver: AudioIdentityResolver,
        audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    ) : this(
        libraryItemDao, libraryDao, linkDao, candidateDao, dismissalDao,
        audioIdentityResolver, audioPlaybackPreferencesStore, System::currentTimeMillis,
    )
```

Add a private helper (place it next to `createUserConfirmedLink`):

```kotlin
    /**
     * Runs [mutate] (a link/unlink change), then migrates the per-book audio-settings record if the
     * change moved the readaloud's canonical audio identity (ADR 0028) — e.g. linking an audiobook
     * moves the saved speed from the Storyteller id onto the audiobook id; unlinking moves it back.
     */
    private suspend fun rekeyAudioSettingsAround(
        storytellerServerId: String,
        storytellerBookId: String,
        mutate: suspend () -> Unit,
    ) {
        val before = audioIdentityResolver.resolveForStorytellerBook(storytellerServerId, storytellerBookId)
        mutate()
        val after = audioIdentityResolver.resolveForStorytellerBook(storytellerServerId, storytellerBookId)
        if (before != after) audioPlaybackPreferencesStore.rekey(before, after)
    }
```

Wrap the three link/unlink operations. Replace `confirmCandidate`:

```kotlin
    override suspend fun confirmCandidate(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    ) {
        rekeyAudioSettingsAround(storytellerServerId, storytellerBookId) {
            createUserConfirmedLink(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
            // The book is now Confirmed; drop all of its Pending-Review candidates.
            candidateDao.deleteByStorytellerBook(storytellerServerId, storytellerBookId)
        }
    }
```

Replace `unlinkBook`:

```kotlin
    override suspend fun unlinkBook(storytellerServerId: String, storytellerBookId: String) {
        rekeyAudioSettingsAround(storytellerServerId, storytellerBookId) {
            linkDao.deleteByStorytellerBook(storytellerServerId, storytellerBookId)
        }
    }
```

Replace `unlinkAbsItem` (it must first find which readaloud the ABS item belonged to):

```kotlin
    override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) {
        val link = linkDao.findByAbsItem(absServerId, absLibraryItemId)
        if (link == null) {
            linkDao.deleteByAbsItem(absServerId, absLibraryItemId)
            return
        }
        rekeyAudioSettingsAround(link.storytellerServerId, link.storytellerBookId) {
            linkDao.deleteByAbsItem(absServerId, absLibraryItemId)
        }
    }
```

Replace `pairManually`:

```kotlin
    override suspend fun pairManually(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    ) {
        rekeyAudioSettingsAround(storytellerServerId, storytellerBookId) {
            createUserConfirmedLink(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
            // Manual pairing overrides any prior "don't ask again" and clears stale candidates.
            dismissalDao.clearBookDismissal(storytellerServerId, storytellerBookId)
            candidateDao.deleteByStorytellerBook(storytellerServerId, storytellerBookId)
        }
    }
```

- [ ] **Step 4: Run the full review-repo test class (new + existing tests) to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :core:data:testDebugUnitTest --tests '*ReadaloudReviewRepositoryTest*'`
Expected: PASS (the three new re-key tests plus all pre-existing tests).

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/ReadaloudReviewRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/ReadaloudReviewRepositoryTest.kt
git commit -m "feat(data): re-key audio settings when an audiobook is linked/unlinked"
```

---

## Task 8: Wire the player to load, apply, and save the speed

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

> The reader ViewModel cannot be JVM-unit-tested (Readium + `android.net.Uri`), so this task is verified by compilation, the lower-level tests above, and the manual check in Task 9 — matching the existing `EpubReaderViewModelTest` boundary.

- [ ] **Step 1: Add constructor dependencies**

In the `@Inject constructor(...)` parameter list (after `private val readaloudLinkRepository: ReadaloudLinkRepository,`), add:

```kotlin
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
```

Add imports near the other `com.riffle.core.domain.*` imports at the top of the file:

```kotlin
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
```

- [ ] **Step 2: Add fields for the resolved identity and the loaded speed**

Next to the existing `audioBookId` / `audioServerId` fields (around line 268), add:

```kotlin
    // The audio-settings key (ADR 0028) — the linked audiobook's id when present, else the Storyteller
    // readaloud id. Distinct from audioBookId/audioServerId, which still locate the readaloud *bundle*.
    private var audioSettingsIdentity: AudioIdentity = AudioIdentity("", itemId)
    // The per-book speed to apply when the player is prepared; the fixed 1x default until loaded.
    private var initialSpeed: Float = AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED
```

- [ ] **Step 3: Resolve the identity and load the saved speed in `init`**

In the `viewModelScope.launch { ... }` block that resolves `audioBookId` / `audioServerId` (around lines 369–375), immediately after the existing lines:

```kotlin
            audioBookId = link?.storytellerBookId ?: itemId
            audioServerId = link?.storytellerServerId ?: activeServer?.id ?: ""
            readerServerId = activeServer?.id
```

add:

```kotlin
            // Resolve the audio-settings key and load the saved speed (ADR 0028). With a link, the
            // resolver prefers the linked audiobook's id; without one, settings key on this ABS item.
            audioSettingsIdentity = if (link != null) {
                audioIdentityResolver.resolveForStorytellerBook(link.storytellerServerId, link.storytellerBookId)
            } else {
                AudioIdentity(activeServer?.id ?: "", itemId)
            }
            initialSpeed = audioPlaybackPreferencesStore.load(audioSettingsIdentity)
                ?: AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED
```

- [ ] **Step 4: Apply the loaded speed when the player is prepared**

In `ensureOpened` (around line 1331), apply the speed right after the player is opened. Replace:

```kotlin
    private suspend fun ensureOpened(bundle: File): com.riffle.core.domain.ReadaloudTrack? {
        val track = ensureTrack(bundle) ?: return null
        if (!readaloudPrepared) {
            playerCoordinator.open(audioBookId, bundle, track)
            readaloudPrepared = true
        }
        return track
    }
```

with:

```kotlin
    private suspend fun ensureOpened(bundle: File): com.riffle.core.domain.ReadaloudTrack? {
        val track = ensureTrack(bundle) ?: return null
        if (!readaloudPrepared) {
            playerCoordinator.open(audioBookId, bundle, track)
            // Apply the persisted per-book speed to the freshly-prepared session. Use the coordinator
            // directly (not the VM's setSpeed) so restoring the saved value doesn't re-save it.
            playerCoordinator.setSpeed(initialSpeed)
            readaloudPrepared = true
        }
        return track
    }
```

- [ ] **Step 5: Persist the speed when the user changes it**

Replace the existing one-liner `setSpeed` (around line 1122):

```kotlin
    fun setSpeed(speed: Float) = playerCoordinator.setSpeed(speed)
```

with:

```kotlin
    fun setSpeed(speed: Float) {
        playerCoordinator.setSpeed(speed)
        viewModelScope.launch { audioPlaybackPreferencesStore.save(audioSettingsIdentity, speed) }
    }
```

- [ ] **Step 6: Build the app module**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(reader): load, apply, and persist per-book readaloud speed"
```

---

## Task 9: Full verification

- [ ] **Step 1: Run the whole JVM test suite (CI parity)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew test`
Expected: BUILD SUCCESSFUL — includes the new store, resolver, and review-repo tests.

- [ ] **Step 2: Build the debug APK to confirm the Hilt graph + Room schema are consistent**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Room verifies the entity matches schema 32; a mismatch fails the build).

- [ ] **Step 3: Run the migration test on your own session AVD**

Boot your dedicated session AVD (see memory: clone a throwaway AVD; never touch other emulators), then run only the database module's connected tests against it by serial. Do NOT run `:app:connectedDebugAndroidTest`.

Run (substitute your AVD serial): `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ANDROID_SERIAL=<your-avd-serial> ./gradlew :core:database:connectedDebugAndroidTest --tests '*MigrationTest.migration31To32*' --tests '*MigrationTest.migrateFullChain*'`
Expected: PASS. (If the AVD is unavailable, note that this connected test was not run.)

- [ ] **Step 4: Manual end-to-end check**

On the running app with a readaloud book:
1. Open the book, open the Readaloud player, change speed to e.g. `1.5×`.
2. Close and reopen the book → the player shows `1.5×` (persisted).
3. Set speed back to `1×`, reopen → shows `1×` (record removed, default applied).
4. (If an audiobook is linkable) In Settings → Storyteller Server → Readaloud matches, link the audiobook, reopen → the previously-set speed still applies (record migrated to the audiobook id).

Expected: all four behave as described.

---

## Self-Review Notes

- **Spec coverage (ADR 0028):** identity resolution (Task 5), dedicated table + fixed-default store with `rekey` (Tasks 2, 4), re-key on link/unlink across `confirmCandidate`/`pairManually`/`unlinkBook`/`unlinkAbsItem` (Task 7), player load/apply/save with bundle resolution untouched (Task 8), Room migration + `MigrationTest` (Tasks 2, 3). All covered.
- **Type consistency:** `AudioIdentity(serverId, bookId)`, `AudioPlaybackPreferencesStore.{load,save,clear,rekey}` + `DEFAULT_PLAYBACK_SPEED`, `AudioIdentityResolver.resolveForStorytellerBook`, DAO `{upsert,get,delete}` are used identically across impl, DI, and tests.
- **No placeholders:** every code step shows complete code; every run step shows the exact command and expected result.
