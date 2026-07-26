# core:sync Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract sync and reconciliation orchestration from `core:data` into a new pure-Kotlin JVM `core:sync` module, making the sync layer JVM-testable without Room or Android.

**Architecture:** Create `core:sync` as a pure-JVM Kotlin module depending only on `core:domain`, `core:models`, `core:catalog`, `core:sources`, and `core:logging`. Interfaces that currently live inline in `ProgressSweep.kt` or in `core:data` are promoted to first-class files in `core:sync`. Room-coupled DAO operations are abstracted behind a new `AudiobookBookmarkSyncStore` interface in `core:domain`; the Room implementation stays in `core:data`.

**Tech Stack:** Kotlin JVM, kotlinx-coroutines, Hilt DI (wiring stays in `core:data`/`app`), JUnit 4 + kotlinx-coroutines-test.

## Global Constraints

- `core:sync` must pass `checkNoAndroidImports` — no `android.*`, `androidx.*` (except `androidx.annotation`), or `java.util.logging` imports in production sources.
- Never use `viewModelScope` in `core:sync`; all coroutine operations take an injected `CoroutineScope`.
- Package for all new `core:sync` production sources: `com.riffle.core.sync`.
- Always use named constants, never string literals for types (per AGENTS.md).
- Run `./gradlew test` (not module-specific) to catch all JVM tests.
- `JAVA_HOME` must point to Android Studio's JBR: `export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr`

---

## File Map

### New files
| Path | Responsibility |
|---|---|
| `core/sync/build.gradle.kts` | Pure-JVM module definition |
| `core/sync/src/main/kotlin/com/riffle/core/sync/AnnotationLockPort.kt` | Moved from `core:data` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/ReconcileLocks.kt` | Moved from `core:data` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/OpenReconcileTargets.kt` | Moved from `core:data` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/CycleOutcome.kt` | Moved + split from `core:data` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/AnnotationSyncStatusStore.kt` | Moved + split from `core:data` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/DirtyProgressLedger.kt` | Extracted from `ProgressSweep.kt` in `core:data` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/DirtyBookmarkLedger.kt` | Extracted from `ProgressSweep.kt` in `core:data` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/BookmarkReconcile.kt` | Extracted from `ProgressSweep.kt` in `core:data` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/ProgressRemoteFactory.kt` | Extracted from `ProgressSweep.kt` in `core:data` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/ProgressSweep.kt` | Moved from `core:data`, now imports from `core:sync` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/AudiobookBookmarkReconciler.kt` | Moved from `core:data`, uses `AudiobookBookmarkSyncStore` |
| `core/sync/src/main/kotlin/com/riffle/core/sync/DirtyAnnotationLedger.kt` | Interface only, moved from `core:data` |
| `core/domain/src/main/kotlin/com/riffle/core/domain/SyncableAudiobookBookmark.kt` | New sync-layer bookmark model |
| `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookBookmarkSyncStore.kt` | New sync-specific DAO abstraction |
| `core/data/src/main/kotlin/com/riffle/core/data/AudiobookBookmarkSyncStoreImpl.kt` | Room impl of `AudiobookBookmarkSyncStore` |
| `core/sync/src/test/kotlin/com/riffle/core/sync/AudiobookBookmarkReconcilerTest.kt` | Moved from `core:data`, uses `SyncableAudiobookBookmark` |
| `core/sync/src/test/kotlin/com/riffle/core/sync/ProgressSweepTest.kt` | Moved from `core:data` (package rename only) |
| `core/sync/src/test/kotlin/com/riffle/core/sync/ProgressSweepBookmarkTest.kt` | Moved from `core:data` (package rename only) |
| `core/sync/src/test/kotlin/com/riffle/core/sync/AnnotationSyncExceptionMappingTest.kt` | Moved from `core:data` (package rename only) |

### Modified files
| Path | Change |
|---|---|
| `settings.gradle.kts` | Add `include(":core:sync")` |
| `core/data/build.gradle.kts` | Add `implementation(project(":core:sync"))` |
| `app/build.gradle.kts` | Add `implementation(project(":core:sync"))` |
| `core/data/src/main/kotlin/com/riffle/core/data/RoomDirtyProgressLedger.kt` | Update `DirtyProgressLedger` import → `core:sync` |
| `core/data/src/main/kotlin/com/riffle/core/data/DirtyAnnotationLedger.kt` | Remove interface, keep only `RoomDirtyAnnotationLedger` impl; import from `core:sync` |
| `core/data/src/main/kotlin/com/riffle/core/data/ItemProgressPuller.kt` | Update `ReconcileLocks`, `OpenReconcileTargets`, `ProgressRemoteFactory` imports → `core:sync` |
| `core/data/src/main/kotlin/com/riffle/core/data/CatalogProgressRemoteFactory.kt` | Update `ProgressRemoteFactory` import → `core:sync` |
| `core/data/src/main/kotlin/com/riffle/core/data/LibraryRepositoryImpl.kt` | Update `DirtyProgressLedger` import → `core:sync` |
| `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSweep.kt` | Update `DirtyAnnotationLedger`, `ReconcileLocks`, `AnnotationSyncStatusStore`, `CycleOutcome` imports → `core:sync` |
| `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSyncController.kt` | Update `AnnotationLockPort`, `ReconcileLocks`, `AnnotationSyncStatusStore`, `CycleOutcome` imports → `core:sync` |
| `core/data/src/main/kotlin/com/riffle/core/data/AnnotationLiveSync.kt` | Update `AnnotationSyncStatusStore`, `CycleOutcome` imports → `core:sync` |
| `core/data/src/main/kotlin/com/riffle/core/data/AnnotationPushCoordinator.kt` | Update `AnnotationLockPort`, `AnnotationSyncStatusStore`, `CycleOutcome` imports → `core:sync` |
| `core/data/src/main/kotlin/com/riffle/core/data/AnnotationMergeOrchestrator.kt` | Update `AnnotationSyncStatusStore`, `CycleOutcome` imports → `core:sync` |
| `core/data/src/main/kotlin/com/riffle/core/data/di/modules/SyncModule.kt` | Update all moved-class imports to `core:sync` package; add `AudiobookBookmarkSyncStore` binding |
| `app/src/main/kotlin/com/riffle/app/sync/ProgressSyncWorker.kt` | Update `ProgressSweep` import → `core:sync` |
| `app/src/main/kotlin/com/riffle/app/sync/AnnotationSyncWorker.kt` | Update `CycleOutcome` import → `core:sync` |
| `app/src/main/kotlin/com/riffle/app/feature/reader/session/ReaderSessionLifecycle.kt` | Update `OpenReconcileTargets` import → `core:sync` |
| `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookReconciliationCoordinator.kt` | Update `OpenReconcileTargets` import → `core:sync` |
| `app/src/main/kotlin/com/riffle/app/feature/settings/annotationsync/AnnotationSyncMaintenanceViewModel.kt` | Update `AnnotationSyncStatusStore` import → `core:sync` |
| `app/src/main/kotlin/com/riffle/app/feature/annotationsync/AnnotationSyncStatus.kt` | Update `CycleOutcome` import → `core:sync` |
| `app/src/main/kotlin/com/riffle/app/feature/reader/session/AnnotationSession.kt` | Update `AnnotationSyncStatusStore`, `CycleOutcome` imports → `core:sync` |
| `app/src/main/kotlin/com/riffle/app/feature/server/AddSourceViewModel.kt` | Update `AnnotationSyncStatusStore`, `CycleOutcome` imports → `core:sync` |
| `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt` | Update `AnnotationSyncStatusStore`, `CycleOutcome` imports → `core:sync` |

### Deleted files (content moved to `core:sync`)
- `core/data/src/main/kotlin/com/riffle/core/data/ReconcileLocks.kt`
- `core/data/src/main/kotlin/com/riffle/core/data/AnnotationLockPort.kt`
- `core/data/src/main/kotlin/com/riffle/core/data/OpenReconcileTargets.kt`
- `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSyncStatusStore.kt` (split into two files in `core:sync`)
- `core/data/src/main/kotlin/com/riffle/core/data/ProgressSweep.kt` (interfaces extracted, all move to `core:sync`)
- `core/data/src/main/kotlin/com/riffle/core/data/AudiobookBookmarkReconciler.kt`
- `core/data/src/test/kotlin/com/riffle/core/data/AudiobookBookmarkReconcilerTest.kt`
- `core/data/src/test/kotlin/com/riffle/core/data/ProgressSweepTest.kt`
- `core/data/src/test/kotlin/com/riffle/core/data/ProgressSweepBookmarkTest.kt`
- `core/data/src/test/kotlin/com/riffle/core/data/AnnotationSyncExceptionMappingTest.kt`
- `core/data/src/test/kotlin/com/riffle/core/data/AnnotationSyncStatusStoreTest.kt`

---

## Task 1: Module scaffold + domain interfaces

**Files:**
- Create: `core/sync/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/SyncableAudiobookBookmark.kt`
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookBookmarkSyncStore.kt`

**Interfaces:**
- Produces: `SyncableAudiobookBookmark(id, sourceId, itemId, positionSec, title, createdAt, localUpdatedAt, lastSyncedAt, deleted)` — used by `AudiobookBookmarkReconciler` in Task 3
- Produces: `AudiobookBookmarkSyncStore` — used by `AudiobookBookmarkReconciler` in Task 3 and `AudiobookBookmarkSyncStoreImpl` in Task 4

- [ ] **Step 1: Add `core:sync` to settings**

In `settings.gradle.kts`, after `include(":core:sources")` add:
```kotlin
include(":core:sync")
```

- [ ] **Step 2: Create `core/sync/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:models"))
    implementation(project(":core:catalog"))
    implementation(project(":core:sources"))
    implementation(project(":core:logging"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 3: Create `SyncableAudiobookBookmark`**

`core/domain/src/main/kotlin/com/riffle/core/domain/SyncableAudiobookBookmark.kt`:
```kotlin
package com.riffle.core.domain

/** Bookmark row as seen by the sync layer — includes dirty-tracking fields. */
data class SyncableAudiobookBookmark(
    val id: String,
    val sourceId: String,
    val itemId: String,
    val positionSec: Double,
    val title: String,
    val createdAt: Long,
    val localUpdatedAt: Long,
    val lastSyncedAt: Long,
    val deleted: Boolean,
)
```

- [ ] **Step 4: Create `AudiobookBookmarkSyncStore`**

`core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookBookmarkSyncStore.kt`:
```kotlin
package com.riffle.core.domain

/** Sync-layer storage contract for audiobook bookmarks. Abstracts Room for `core:sync`. */
interface AudiobookBookmarkSyncStore {
    /** All rows including tombstones and dirty entries. */
    suspend fun allForItemIncludingDeleted(sourceId: String, itemId: String): List<SyncableAudiobookBookmark>

    suspend fun upsert(bookmark: SyncableAudiobookBookmark)

    /** Compare-and-clear: mark clean after a successful push. Returns whether the row was touched. */
    suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Boolean

    /** Hard-remove a deleted tombstone if still unchanged. Returns whether the row was removed. */
    suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Boolean

    suspend fun hardDelete(id: String)
}
```

- [ ] **Step 5: Verify domain module compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr"
./gradlew :core:domain:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts core/sync/build.gradle.kts \
  core/domain/src/main/kotlin/com/riffle/core/domain/SyncableAudiobookBookmark.kt \
  core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookBookmarkSyncStore.kt
git commit -m "feat(sync): scaffold core:sync module + AudiobookBookmarkSyncStore interface"
```

---

## Task 2: Move pure sync types to `core:sync`

**Files:**
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/AnnotationLockPort.kt`
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/ReconcileLocks.kt`
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/OpenReconcileTargets.kt`
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/CycleOutcome.kt`
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/AnnotationSyncStatusStore.kt`
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/DirtyProgressLedger.kt`
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/DirtyBookmarkLedger.kt`
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/BookmarkReconcile.kt`
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/ProgressRemoteFactory.kt`
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/DirtyAnnotationLedger.kt`

**Interfaces:**
- Produces: All interface types that `ProgressSweep` (Task 3) and `core:data` remaining files consume.

- [ ] **Step 1: Create `AnnotationLockPort`**

`core/sync/src/main/kotlin/com/riffle/core/sync/AnnotationLockPort.kt`:
```kotlin
package com.riffle.core.sync

interface AnnotationLockPort {
    suspend fun <T> withAnnotationLock(sourceId: String, itemId: String, block: suspend () -> T): T
}
```

- [ ] **Step 2: Create `ReconcileLocks`**

`core/sync/src/main/kotlin/com/riffle/core/sync/ReconcileLocks.kt`:
```kotlin
package com.riffle.core.sync

import com.riffle.core.domain.RemoteKind
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-resource reconcile mutexes (#321). Held by background sweeps and the live reader/player so a
 * given remote resource is reconciled by exactly one of them at a time.
 */
@Singleton
class ReconcileLocks @Inject constructor() : AnnotationLockPort {
    private val progressMutexes = ConcurrentHashMap<String, Mutex>()
    private val annotationMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(sourceId: String, itemId: String, kind: RemoteKind, block: suspend () -> T): T {
        val mutex = progressMutexes.getOrPut("$sourceId $itemId $kind") { Mutex() }
        return mutex.withLock { block() }
    }

    override suspend fun <T> withAnnotationLock(sourceId: String, itemId: String, block: suspend () -> T): T {
        val mutex = annotationMutexes.getOrPut("$sourceId $itemId") { Mutex() }
        return mutex.withLock { block() }
    }
}
```

- [ ] **Step 3: Create `OpenReconcileTargets`**

`core/sync/src/main/kotlin/com/riffle/core/sync/OpenReconcileTargets.kt`:
```kotlin
package com.riffle.core.sync

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The set of (sourceId, itemId) a live surface is currently driving (ADR 0030).
 * The durable sweep skips these to avoid cross-device erase races.
 */
@Singleton
class OpenReconcileTargets @Inject constructor() {
    private val open = ConcurrentHashMap.newKeySet<String>()

    private fun key(sourceId: String, itemId: String) = "$sourceId $itemId"

    fun markOpen(sourceId: String, itemId: String) { open.add(key(sourceId, itemId)) }
    fun markClosed(sourceId: String, itemId: String) { open.remove(key(sourceId, itemId)) }
    fun isOpen(sourceId: String, itemId: String): Boolean = open.contains(key(sourceId, itemId))
}
```

- [ ] **Step 4: Create `CycleOutcome`**

`core/sync/src/main/kotlin/com/riffle/core/sync/CycleOutcome.kt`:
```kotlin
package com.riffle.core.sync

import com.riffle.core.sources.webdav.AnnotationSyncException

sealed class CycleOutcome {
    object NeverRun : CycleOutcome()
    data class Success(val atMs: Long) : CycleOutcome()

    sealed class Failed(open val atMs: Long) : CycleOutcome() {
        data class Network(override val atMs: Long, val message: String?) : Failed(atMs)
        data class Auth(override val atMs: Long, val code: Int) : Failed(atMs)
        data class Tls(override val atMs: Long, val message: String?) : Failed(atMs)
        data class Server(override val atMs: Long, val code: Int) : Failed(atMs)
        data class Unknown(override val atMs: Long, val message: String?) : Failed(atMs)
    }
}

fun Throwable.toFailedCycleOutcome(at: Long): CycleOutcome.Failed = when (this) {
    is AnnotationSyncException.AuthFailed -> CycleOutcome.Failed.Auth(at, code)
    is AnnotationSyncException.HttpFailure -> CycleOutcome.Failed.Server(at, code)
    is AnnotationSyncException.NetworkError -> CycleOutcome.Failed.Network(at, message)
    is AnnotationSyncException.TlsError -> CycleOutcome.Failed.Tls(at, message)
    else -> CycleOutcome.Failed.Unknown(at, message)
}
```

- [ ] **Step 5: Create `AnnotationSyncStatusStore`**

`core/sync/src/main/kotlin/com/riffle/core/sync/AnnotationSyncStatusStore.kt`:
```kotlin
package com.riffle.core.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AnnotationSyncStatusStore @Inject constructor() {
    private val _lastCycleOutcome = MutableStateFlow<CycleOutcome>(CycleOutcome.NeverRun)
    val lastCycleOutcome: StateFlow<CycleOutcome> = _lastCycleOutcome.asStateFlow()

    private val _lastSuccessAtMs = MutableStateFlow<Long?>(null)
    val lastSuccessAtMs: StateFlow<Long?> = _lastSuccessAtMs.asStateFlow()

    fun report(outcome: CycleOutcome) {
        _lastCycleOutcome.value = outcome
        if (outcome is CycleOutcome.Success) _lastSuccessAtMs.value = outcome.atMs
    }
}
```

- [ ] **Step 6: Create the four `ProgressSweep` interface files**

`core/sync/src/main/kotlin/com/riffle/core/sync/DirtyProgressLedger.kt`:
```kotlin
package com.riffle.core.sync

interface DirtyProgressLedger {
    suspend fun serversWithDirty(): List<String>
    suspend fun dirtyEbookItems(sourceId: String): List<String>
    suspend fun dirtyAudioItems(sourceId: String): List<String>
}
```

`core/sync/src/main/kotlin/com/riffle/core/sync/DirtyBookmarkLedger.kt`:
```kotlin
package com.riffle.core.sync

interface DirtyBookmarkLedger {
    suspend fun serversWithDirty(): List<String>
    suspend fun dirtyItems(sourceId: String): List<String>
}
```

`core/sync/src/main/kotlin/com/riffle/core/sync/BookmarkReconcile.kt`:
```kotlin
package com.riffle.core.sync

fun interface BookmarkReconcile {
    suspend fun reconcile(sourceId: String, itemId: String)
}
```

`core/sync/src/main/kotlin/com/riffle/core/sync/ProgressRemoteFactory.kt`:
```kotlin
package com.riffle.core.sync

import com.riffle.core.domain.ProgressRemote

interface ProgressRemoteFactory {
    suspend fun ebook(sourceId: String, itemId: String): ProgressRemote<String>?
    suspend fun audio(sourceId: String, itemId: String): ProgressRemote<Double>?
}
```

- [ ] **Step 7: Create `DirtyAnnotationLedger` interface**

`core/sync/src/main/kotlin/com/riffle/core/sync/DirtyAnnotationLedger.kt`:
```kotlin
package com.riffle.core.sync

/** Enumerates (sourceId, itemId) pairs with at least one dirty annotation row. */
fun interface DirtyAnnotationLedger {
    suspend fun dirtySourceItems(): List<DirtySourceItem>

    data class DirtySourceItem(val sourceId: String, val itemId: String)
}
```

Note: `DirtyAnnotationLedger` currently returns `List<AnnotationDao.DirtySourceItem>` which is a Room type. We extract the data class into `core:sync` itself so no Room dep is needed.

- [ ] **Step 8: Verify `core:sync` compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr"
./gradlew :core:sync:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add core/sync/src/main/kotlin/com/riffle/core/sync/
git commit -m "feat(sync): move pure sync types — locks, targets, CycleOutcome, ledger interfaces"
```

---

## Task 3: Move `ProgressSweep` and `AudiobookBookmarkReconciler` to `core:sync`

**Files:**
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/ProgressSweep.kt`
- Create: `core/sync/src/main/kotlin/com/riffle/core/sync/AudiobookBookmarkReconciler.kt`

**Interfaces:**
- Consumes: `DirtyProgressLedger`, `DirtyBookmarkLedger`, `BookmarkReconcile`, `ProgressRemoteFactory`, `ReconcileLocks`, `OpenReconcileTargets` (all from Task 2 — same package)
- Consumes: `AudiobookBookmarkSyncStore`, `SyncableAudiobookBookmark` (from Task 1 — `core:domain`)
- Consumes: `CatalogRegistry`, `ProgressPeerCapability`, `AudiobookProgressPeerCapability` (from `core:catalog`)
- Consumes: `ProgressReconciler`, `ProgressRemote`, `RemoteKind` (from `core:domain`)
- Produces: `ProgressSweep.run()`, `AudiobookBookmarkReconciler.reconcile(sourceId, itemId)` — consumed by `core:data` DI wiring and `app`

- [ ] **Step 1: Create `ProgressSweep`**

`core/sync/src/main/kotlin/com/riffle/core/sync/ProgressSweep.kt`:
```kotlin
package com.riffle.core.sync

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.RemoteKind

class ProgressSweep(
    private val ledger: DirtyProgressLedger,
    private val catalogRegistry: CatalogRegistry,
    private val ebookReconciler: ProgressReconciler<String>,
    private val audioReconciler: ProgressReconciler<Double>,
    private val remoteFactory: ProgressRemoteFactory,
    private val locks: ReconcileLocks,
    private val openTargets: OpenReconcileTargets,
    private val bookmarkLedger: DirtyBookmarkLedger,
    private val bookmarkReconcile: BookmarkReconcile,
) {
    suspend fun run() {
        val sources = (ledger.serversWithDirty() + bookmarkLedger.serversWithDirty()).distinct()
        for (sourceId in sources) {
            val catalog = catalogRegistry.forSourceId(sourceId) ?: continue
            val isProgressPeer = catalog is ProgressPeerCapability
            val isAudioPeer = catalog is AudiobookProgressPeerCapability
            if (isProgressPeer) for (itemId in ledger.dirtyEbookItems(sourceId)) {
                if (openTargets.isOpen(sourceId, itemId)) continue
                val remote = remoteFactory.ebook(sourceId, itemId) ?: continue
                locks.withLock(sourceId, itemId, RemoteKind.EBOOK_POSITION) {
                    ebookReconciler.reconcile(sourceId, itemId, remote)
                }
            }
            if (isAudioPeer) for (itemId in ledger.dirtyAudioItems(sourceId)) {
                if (openTargets.isOpen(sourceId, itemId)) continue
                val remote = remoteFactory.audio(sourceId, itemId) ?: continue
                locks.withLock(sourceId, itemId, RemoteKind.AUDIO_POSITION) {
                    audioReconciler.reconcile(sourceId, itemId, remote)
                }
            }
            for (itemId in bookmarkLedger.dirtyItems(sourceId)) {
                if (openTargets.isOpen(sourceId, itemId)) continue
                locks.withLock(sourceId, itemId, RemoteKind.AUDIOBOOK_BOOKMARK) {
                    bookmarkReconcile.reconcile(sourceId, itemId)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Create `AudiobookBookmarkReconciler`**

`core/sync/src/main/kotlin/com/riffle/core/sync/AudiobookBookmarkReconciler.kt`:
```kotlin
package com.riffle.core.sync

import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.domain.AudiobookBookmarkSyncStore
import com.riffle.core.domain.SyncableAudiobookBookmark
import javax.inject.Inject
import kotlin.math.roundToInt

class AudiobookBookmarkReconciler(
    private val store: AudiobookBookmarkSyncStore,
    private val catalogRegistry: CatalogRegistry,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    @Inject
    constructor(store: AudiobookBookmarkSyncStore, catalogRegistry: CatalogRegistry) : this(
        store,
        catalogRegistry,
        System::currentTimeMillis,
        { java.util.UUID.randomUUID().toString() },
    )

    suspend fun reconcile(sourceId: String, itemId: String) {
        val catalog = catalogRegistry.forSourceId(sourceId) ?: return
        val cap = catalog as? BookmarksCapability ?: return

        val dirty = store.allForItemIncludingDeleted(sourceId, itemId)
            .filter { it.localUpdatedAt > it.lastSyncedAt }
        for (row in dirty) {
            val ifStamp = row.localUpdatedAt
            if (row.deleted) {
                val ok = runCatching { cap.deleteBookmark(itemId, row.positionSec.roundToInt()) }.isSuccess
                if (ok) store.hardDeleteIfUnchanged(row.id, ifLocalUpdatedAt = ifStamp)
            } else {
                val ok = runCatching {
                    if (row.lastSyncedAt == 0L) cap.createBookmark(itemId, row.positionSec.roundToInt(), row.title)
                    else cap.renameBookmark(itemId, row.positionSec.roundToInt(), row.title)
                }.isSuccess
                if (ok) store.confirmPushedIfUnchanged(row.id, serverStamp = now(), ifLocalUpdatedAt = ifStamp)
            }
        }

        val remoteAll = runCatching { cap.listAllBookmarks() }.getOrNull() ?: return
        val serverForItem = remoteAll.filter { it.itemId == itemId }
        val serverTimes = serverForItem.map { it.timeSec }.toSet()
        val local = store.allForItemIncludingDeleted(sourceId, itemId)

        for (sb in serverForItem) {
            val atTime = local.firstOrNull { it.positionSec.roundToInt() == sb.timeSec }
            when {
                atTime == null -> store.upsert(
                    SyncableAudiobookBookmark(
                        id = newId(), sourceId = sourceId, itemId = itemId,
                        positionSec = sb.timeSec.toDouble(), title = sb.title,
                        createdAt = sb.createdAt, localUpdatedAt = now(), lastSyncedAt = now(),
                        deleted = false,
                    ),
                )
                atTime.localUpdatedAt <= atTime.lastSyncedAt && !atTime.deleted && atTime.title != sb.title ->
                    store.upsert(atTime.copy(title = sb.title, localUpdatedAt = now(), lastSyncedAt = now()))
            }
        }

        for (lb in local) {
            if (!lb.deleted && lb.localUpdatedAt <= lb.lastSyncedAt && lb.positionSec.roundToInt() !in serverTimes) {
                store.hardDelete(lb.id)
            }
        }
    }
}
```

- [ ] **Step 3: Verify `core:sync` compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr"
./gradlew :core:sync:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/sync/src/main/kotlin/com/riffle/core/sync/ProgressSweep.kt \
        core/sync/src/main/kotlin/com/riffle/core/sync/AudiobookBookmarkReconciler.kt
git commit -m "feat(sync): add ProgressSweep + AudiobookBookmarkReconciler to core:sync"
```

---

## Task 4: Add `AudiobookBookmarkSyncStoreImpl` to `core:data` and update DI

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/AudiobookBookmarkSyncStoreImpl.kt`
- Modify: `core/data/build.gradle.kts`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/modules/SyncModule.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/RoomDirtyProgressLedger.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/DirtyAnnotationLedger.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/CatalogProgressRemoteFactory.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/LibraryRepositoryImpl.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ItemProgressPuller.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSweep.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSyncController.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationLiveSync.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationPushCoordinator.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationMergeOrchestrator.kt`
- Delete: `core/data/src/main/kotlin/com/riffle/core/data/ReconcileLocks.kt`
- Delete: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationLockPort.kt`
- Delete: `core/data/src/main/kotlin/com/riffle/core/data/OpenReconcileTargets.kt`
- Delete: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSyncStatusStore.kt`
- Delete: `core/data/src/main/kotlin/com/riffle/core/data/ProgressSweep.kt`
- Delete: `core/data/src/main/kotlin/com/riffle/core/data/AudiobookBookmarkReconciler.kt`

**Interfaces:**
- Consumes: `AudiobookBookmarkSyncStore` (from Task 1), all `core:sync` types (from Tasks 2–3)
- Produces: `AudiobookBookmarkSyncStoreImpl` — injected into `AudiobookBookmarkReconciler` via Hilt

- [ ] **Step 1: Add `core:sync` to `core:data` dependencies**

In `core/data/build.gradle.kts`, inside `dependencies { }`:
```kotlin
implementation(project(":core:sync"))
```

- [ ] **Step 2: Create `AudiobookBookmarkSyncStoreImpl`**

`core/data/src/main/kotlin/com/riffle/core/data/AudiobookBookmarkSyncStoreImpl.kt`:
```kotlin
package com.riffle.core.data

import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.domain.AudiobookBookmarkSyncStore
import com.riffle.core.domain.SyncableAudiobookBookmark
import javax.inject.Inject

class AudiobookBookmarkSyncStoreImpl @Inject constructor(
    private val dao: AudiobookBookmarkDao,
) : AudiobookBookmarkSyncStore {

    override suspend fun allForItemIncludingDeleted(sourceId: String, itemId: String): List<SyncableAudiobookBookmark> =
        dao.allForItem(sourceId, itemId).map { it.toSyncable() }

    override suspend fun upsert(bookmark: SyncableAudiobookBookmark) =
        dao.upsert(bookmark.toEntity())

    override suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Boolean =
        dao.confirmPushedIfUnchanged(id, serverStamp, ifLocalUpdatedAt) > 0

    override suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Boolean =
        dao.hardDeleteIfUnchanged(id, ifLocalUpdatedAt) > 0

    override suspend fun hardDelete(id: String) = dao.hardDelete(id)

    private fun AudiobookBookmarkEntity.toSyncable() = SyncableAudiobookBookmark(
        id = id, sourceId = sourceId, itemId = itemId, positionSec = positionSec,
        title = title, createdAt = createdAt, localUpdatedAt = localUpdatedAt,
        lastSyncedAt = lastSyncedAt, deleted = deleted,
    )

    private fun SyncableAudiobookBookmark.toEntity() = AudiobookBookmarkEntity(
        id = id, sourceId = sourceId, itemId = itemId, positionSec = positionSec,
        title = title, createdAt = createdAt, localUpdatedAt = localUpdatedAt,
        lastSyncedAt = lastSyncedAt, deleted = deleted,
    )
}
```

- [ ] **Step 3: Delete moved source files from `core:data`**

```bash
rm core/data/src/main/kotlin/com/riffle/core/data/ReconcileLocks.kt
rm core/data/src/main/kotlin/com/riffle/core/data/AnnotationLockPort.kt
rm core/data/src/main/kotlin/com/riffle/core/data/OpenReconcileTargets.kt
rm core/data/src/main/kotlin/com/riffle/core/data/AnnotationSyncStatusStore.kt
rm core/data/src/main/kotlin/com/riffle/core/data/ProgressSweep.kt
rm core/data/src/main/kotlin/com/riffle/core/data/AudiobookBookmarkReconciler.kt
```

- [ ] **Step 4: Update `DirtyAnnotationLedger.kt` — remove interface, keep only `RoomDirtyAnnotationLedger`**

The `DirtyAnnotationLedger` fun-interface currently returns `List<AnnotationDao.DirtySourceItem>`. Now that the interface lives in `core:sync` with its own `DirtyAnnotationLedger.DirtySourceItem` data class, the Room impl in `core:data` must adapt:

Replace the full content of `core/data/src/main/kotlin/com/riffle/core/data/DirtyAnnotationLedger.kt` with:
```kotlin
package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.sync.DirtyAnnotationLedger
import javax.inject.Inject

class RoomDirtyAnnotationLedger @Inject constructor(
    private val annotationDao: AnnotationDao,
) : DirtyAnnotationLedger {
    override suspend fun dirtySourceItems(): List<DirtyAnnotationLedger.DirtySourceItem> =
        annotationDao.dirtySourceItems().map { DirtyAnnotationLedger.DirtySourceItem(it.sourceId, it.itemId) }
}
```

- [ ] **Step 5: Update `RoomDirtyProgressLedger.kt`** — change `DirtyProgressLedger` import to `core:sync`

Replace:
```kotlin
) : DirtyProgressLedger {
```
With the import added at the top:
```kotlin
import com.riffle.core.sync.DirtyProgressLedger
```
(The class body is unchanged.)

- [ ] **Step 6: Update `CatalogProgressRemoteFactory.kt`**

Add import:
```kotlin
import com.riffle.core.sync.ProgressRemoteFactory
```
Remove any reference to the old `com.riffle.core.data.ProgressRemoteFactory`.

- [ ] **Step 7: Update `LibraryRepositoryImpl.kt`**

Change import:
```
com.riffle.core.data.DirtyProgressLedger  →  com.riffle.core.sync.DirtyProgressLedger
```

- [ ] **Step 8: Update `ItemProgressPuller.kt`**

Change imports:
```
com.riffle.core.data.ReconcileLocks       →  com.riffle.core.sync.ReconcileLocks
com.riffle.core.data.OpenReconcileTargets →  com.riffle.core.sync.OpenReconcileTargets
com.riffle.core.data.ProgressRemoteFactory→  com.riffle.core.sync.ProgressRemoteFactory
```

- [ ] **Step 9: Update annotation-sync files in `core:data`**

For each file below, replace the corresponding `com.riffle.core.data.*` imports with `com.riffle.core.sync.*`:

`AnnotationSweep.kt`:
- `com.riffle.core.data.DirtyAnnotationLedger` → `com.riffle.core.sync.DirtyAnnotationLedger`
- `com.riffle.core.data.ReconcileLocks` → `com.riffle.core.sync.ReconcileLocks`
- `com.riffle.core.data.AnnotationSyncStatusStore` → `com.riffle.core.sync.AnnotationSyncStatusStore`
- `com.riffle.core.data.CycleOutcome` → `com.riffle.core.sync.CycleOutcome`
- `com.riffle.core.data.toFailedCycleOutcome` → `com.riffle.core.sync.toFailedCycleOutcome`

Also update the default value of `dirtyLedger` param — the lambda `DirtyAnnotationLedger { annotationDao.dirtySourceItems() }` must now map to the `core:sync` `DirtySourceItem`:
```kotlin
private val dirtyLedger: DirtyAnnotationLedger =
    DirtyAnnotationLedger { annotationDao.dirtySourceItems().map { DirtyAnnotationLedger.DirtySourceItem(it.sourceId, it.itemId) } },
```

`AnnotationSyncController.kt`:
- `com.riffle.core.data.AnnotationLockPort` → `com.riffle.core.sync.AnnotationLockPort`
- `com.riffle.core.data.ReconcileLocks` → `com.riffle.core.sync.ReconcileLocks`
- `com.riffle.core.data.AnnotationSyncStatusStore` → `com.riffle.core.sync.AnnotationSyncStatusStore`
- `com.riffle.core.data.CycleOutcome` → `com.riffle.core.sync.CycleOutcome`

`AnnotationLiveSync.kt`:
- `com.riffle.core.data.AnnotationSyncStatusStore` → `com.riffle.core.sync.AnnotationSyncStatusStore`
- `com.riffle.core.data.CycleOutcome` → `com.riffle.core.sync.CycleOutcome`
- `com.riffle.core.data.toFailedCycleOutcome` → `com.riffle.core.sync.toFailedCycleOutcome`

`AnnotationPushCoordinator.kt`:
- `com.riffle.core.data.AnnotationLockPort` → `com.riffle.core.sync.AnnotationLockPort`
- `com.riffle.core.data.AnnotationSyncStatusStore` → `com.riffle.core.sync.AnnotationSyncStatusStore`
- `com.riffle.core.data.CycleOutcome` → `com.riffle.core.sync.CycleOutcome`
- `com.riffle.core.data.toFailedCycleOutcome` → `com.riffle.core.sync.toFailedCycleOutcome`

`AnnotationMergeOrchestrator.kt`:
- `com.riffle.core.data.AnnotationSyncStatusStore` → `com.riffle.core.sync.AnnotationSyncStatusStore`
- `com.riffle.core.data.CycleOutcome` → `com.riffle.core.sync.CycleOutcome`
- `com.riffle.core.data.toFailedCycleOutcome` → `com.riffle.core.sync.toFailedCycleOutcome`

- [ ] **Step 10: Update `SyncModule.kt`**

Update all `com.riffle.core.data.*` references for the moved classes. Key changes:

```kotlin
// Bindings — change interface FQNs:
abstract fun bindDirtyProgressLedger(impl: com.riffle.core.data.RoomDirtyProgressLedger): com.riffle.core.sync.DirtyProgressLedger
abstract fun bindDirtyAnnotationLedger(impl: com.riffle.core.data.RoomDirtyAnnotationLedger): com.riffle.core.sync.DirtyAnnotationLedger
abstract fun bindProgressRemoteFactory(impl: com.riffle.core.data.CatalogProgressRemoteFactory): com.riffle.core.sync.ProgressRemoteFactory
abstract fun bindItemProgressPuller(impl: com.riffle.core.data.ReconcilingItemProgressPuller): com.riffle.core.data.ItemProgressPuller

// Add new binding:
@Binds @Singleton
abstract fun bindAudiobookBookmarkSyncStore(impl: com.riffle.core.data.AudiobookBookmarkSyncStoreImpl): com.riffle.core.domain.AudiobookBookmarkSyncStore

// provideProgressSweep — update all class FQNs:
fun provideProgressSweep(
    ledger: com.riffle.core.sync.DirtyProgressLedger,
    catalogRegistry: CatalogRegistry,
    remoteFactory: com.riffle.core.sync.ProgressRemoteFactory,
    locks: com.riffle.core.sync.ReconcileLocks,
    openTargets: com.riffle.core.sync.OpenReconcileTargets,
    ebookStore: ReadingPositionStoreImpl,
    audioStore: AudiobookPositionStoreImpl,
    bookmarkDao: com.riffle.core.database.AudiobookBookmarkDao,
    bookmarkReconciler: com.riffle.core.sync.AudiobookBookmarkReconciler,
    uiProgressSink: com.riffle.core.data.LibraryItemUiProgressSink,
): com.riffle.core.sync.ProgressSweep =
    com.riffle.core.sync.ProgressSweep(
        ledger, catalogRegistry,
        com.riffle.core.domain.ProgressReconciler(ebookStore, uiProgressSink),
        com.riffle.core.domain.ProgressReconciler(audioStore, uiProgressSink),
        remoteFactory, locks, openTargets,
        object : com.riffle.core.sync.DirtyBookmarkLedger {
            override suspend fun serversWithDirty() = bookmarkDao.sourcesWithDirtyRows()
            override suspend fun dirtyItems(sourceId: String) =
                bookmarkDao.dirtyForSource(sourceId).map { it.itemId }.distinct()
        },
        com.riffle.core.sync.BookmarkReconcile { sourceId, itemId ->
            bookmarkReconciler.reconcile(sourceId, itemId)
        },
    )

// provideAnnotationSweep and provideAnnotationSyncController — update lock/status-store FQNs:
// ReconcileLocks     → com.riffle.core.sync.ReconcileLocks
// AnnotationSyncStatusStore → com.riffle.core.sync.AnnotationSyncStatusStore
// DirtyAnnotationLedger → com.riffle.core.sync.DirtyAnnotationLedger
```

- [ ] **Step 11: Verify `core:data` compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr"
./gradlew :core:data:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 12: Commit**

```bash
git add core/data/build.gradle.kts \
        core/data/src/main/kotlin/com/riffle/core/data/
git commit -m "refactor(data): wire AudiobookBookmarkSyncStore + update all imports to core:sync"
```

---

## Task 5: Update `app` module imports

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/com/riffle/app/sync/ProgressSyncWorker.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/sync/AnnotationSyncWorker.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/session/ReaderSessionLifecycle.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookReconciliationCoordinator.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/annotationsync/AnnotationSyncMaintenanceViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/annotationsync/AnnotationSyncStatus.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/session/AnnotationSession.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/server/AddSourceViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add `core:sync` to `app` dependencies**

In `app/build.gradle.kts`, add inside `dependencies { }`:
```kotlin
implementation(project(":core:sync"))
```

- [ ] **Step 2: Update all `app` imports**

For each file, replace `com.riffle.core.data.X` with `com.riffle.core.sync.X` for these symbols:
`ProgressSweep`, `CycleOutcome`, `toFailedCycleOutcome`, `OpenReconcileTargets`, `AnnotationSyncStatusStore`

File-by-file:
- `ProgressSyncWorker.kt`: `com.riffle.core.data.ProgressSweep` → `com.riffle.core.sync.ProgressSweep`
- `AnnotationSyncWorker.kt`: `com.riffle.core.data.CycleOutcome` → `com.riffle.core.sync.CycleOutcome`
- `ReaderSessionLifecycle.kt`: `com.riffle.core.data.OpenReconcileTargets` → `com.riffle.core.sync.OpenReconcileTargets`
- `AudiobookReconciliationCoordinator.kt`: `com.riffle.core.data.OpenReconcileTargets` → `com.riffle.core.sync.OpenReconcileTargets`
- `AnnotationSyncMaintenanceViewModel.kt`: `com.riffle.core.data.AnnotationSyncStatusStore` → `com.riffle.core.sync.AnnotationSyncStatusStore`
- `AnnotationSyncStatus.kt`: `com.riffle.core.data.CycleOutcome` → `com.riffle.core.sync.CycleOutcome`
- `AnnotationSession.kt`: both `AnnotationSyncStatusStore` and `CycleOutcome`
- `AddSourceViewModel.kt`: both `AnnotationSyncStatusStore` and `CycleOutcome`
- `SettingsViewModel.kt`: both `AnnotationSyncStatusStore` and `CycleOutcome`

- [ ] **Step 3: Verify `app` compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr"
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/
git commit -m "refactor(app): update imports from core:data → core:sync for moved types"
```

---

## Task 6: Move tests to `core:sync`

**Files:**
- Create: `core/sync/src/test/kotlin/com/riffle/core/sync/ProgressSweepTest.kt`
- Create: `core/sync/src/test/kotlin/com/riffle/core/sync/ProgressSweepBookmarkTest.kt`
- Create: `core/sync/src/test/kotlin/com/riffle/core/sync/AnnotationSyncExceptionMappingTest.kt`
- Create: `core/sync/src/test/kotlin/com/riffle/core/sync/AnnotationSyncStatusStoreTest.kt`
- Create: `core/sync/src/test/kotlin/com/riffle/core/sync/AudiobookBookmarkReconcilerTest.kt`
- Delete: the five corresponding files from `core/data/src/test/kotlin/com/riffle/core/data/`

- [ ] **Step 1: Copy + repackage `ProgressSweepTest` and `ProgressSweepBookmarkTest`**

Copy the two files verbatim, changing only:
1. `package com.riffle.core.data` → `package com.riffle.core.sync`
2. All `import com.riffle.core.data.X` where `X` is a moved type → `import com.riffle.core.sync.X`

The fakes and test logic are unchanged — they already depend only on the interfaces (no Room/Android).

- [ ] **Step 2: Copy + repackage `AnnotationSyncExceptionMappingTest` and `AnnotationSyncStatusStoreTest`**

Same process: change package and `core.data` imports to `core.sync`.

- [ ] **Step 3: Rewrite `AudiobookBookmarkReconcilerTest` for `SyncableAudiobookBookmark`**

The test currently uses `AudiobookBookmarkDao` and `AudiobookBookmarkEntity` (Room). Replace with an in-memory `AudiobookBookmarkSyncStore` fake backed by a `MutableList<SyncableAudiobookBookmark>`.

`core/sync/src/test/kotlin/com/riffle/core/sync/AudiobookBookmarkReconcilerTest.kt`:
```kotlin
package com.riffle.core.sync

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogBookmark
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.SortKey
import com.riffle.core.domain.AudiobookBookmarkSyncStore
import com.riffle.core.domain.SyncableAudiobookBookmark
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookBookmarkReconcilerTest {

    private class FakeStore : AudiobookBookmarkSyncStore {
        val rows = mutableListOf<SyncableAudiobookBookmark>()

        override suspend fun allForItemIncludingDeleted(sourceId: String, itemId: String) =
            rows.filter { it.sourceId == sourceId && it.itemId == itemId }

        override suspend fun upsert(bookmark: SyncableAudiobookBookmark) {
            rows.removeAll { it.id == bookmark.id }
            rows.add(bookmark)
        }

        override suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Boolean {
            val idx = rows.indexOfFirst { it.id == id && it.localUpdatedAt == ifLocalUpdatedAt }
            if (idx < 0) return false
            rows[idx] = rows[idx].copy(localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return true
        }

        override suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Boolean {
            val row = rows.firstOrNull { it.id == id && it.deleted && it.localUpdatedAt == ifLocalUpdatedAt }
                ?: return false
            rows.remove(row)
            return true
        }

        override suspend fun hardDelete(id: String) { rows.removeAll { it.id == id } }

        fun getById(id: String) = rows.firstOrNull { it.id == id }
    }

    private class FakeCatalog(
        var listResult: Result<List<CatalogBookmark>> = Result.success(emptyList()),
    ) : Catalog, BookmarksCapability {
        data class Call(val kind: String, val itemId: String, val timeSec: Int, val title: String)
        val calls = mutableListOf<Call>()
        var createOk = true; var renameOk = true; var deleteOk = true

        override val sourceType = SourceType.ABS
        override suspend fun listRoots() = emptyList<CatalogRoot>()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) = emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun openFile(itemId: String, format: BookFormat, handleHint: String?): CatalogFileStream = throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)
        override suspend fun listAllBookmarks() = listResult.getOrThrow()
        override suspend fun createBookmark(itemId: String, timeSec: Int, title: String): CatalogBookmark {
            calls += Call("create", itemId, timeSec, title)
            if (!createOk) throw RuntimeException("boom")
            return CatalogBookmark(itemId, timeSec, title, createdAt = 0L)
        }
        override suspend fun deleteBookmark(itemId: String, timeSec: Int) {
            calls += Call("delete", itemId, timeSec, "")
            if (!deleteOk) throw RuntimeException("boom")
        }
        override suspend fun renameBookmark(itemId: String, timeSec: Int, newTitle: String): CatalogBookmark {
            calls += Call("update", itemId, timeSec, newTitle)
            if (!renameOk) throw RuntimeException("boom")
            return CatalogBookmark(itemId, timeSec, newTitle, createdAt = 0L)
        }
    }

    private class FakeRegistry(private val catalog: Catalog) : CatalogRegistry {
        override suspend fun forActive(): Catalog = catalog
        override suspend fun forSource(source: Source): Catalog = catalog
        override suspend fun forSourceId(sourceId: String): Catalog = catalog
    }

    private val now = { 1000L }
    private fun counterIds(): () -> String { var n = 0; return { "gen-${n++}" } }

    private fun reconciler(store: FakeStore, catalog: FakeCatalog) =
        AudiobookBookmarkReconciler(store, FakeRegistry(catalog), now = now, newId = counterIds())

    private fun bookmark(
        id: String, positionSec: Double, title: String,
        localUpdatedAt: Long, lastSyncedAt: Long, deleted: Boolean = false,
    ) = SyncableAudiobookBookmark(
        id = id, sourceId = "s1", itemId = "i1", positionSec = positionSec, title = title,
        createdAt = 500L, localUpdatedAt = localUpdatedAt, lastSyncedAt = lastSyncedAt, deleted = deleted,
    )

    @Test fun pushCreate() = runTest {
        val store = FakeStore()
        store.upsert(bookmark("a", 12.4, "Intro", localUpdatedAt = 800L, lastSyncedAt = 0L))
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 12, "Intro", 500L))))
        reconciler(store, cat).reconcile("s1", "i1")

        assertEquals(listOf(FakeCatalog.Call("create", "i1", 12, "Intro")), cat.calls.filter { it.kind == "create" })
        val row = store.getById("a")!!
        assertTrue("created row must become clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pushRename() = runTest {
        val store = FakeStore()
        store.upsert(bookmark("a", 30.0, "New name", localUpdatedAt = 900L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 30, "New name", 500L))))
        reconciler(store, cat).reconcile("s1", "i1")

        assertEquals(listOf(FakeCatalog.Call("update", "i1", 30, "New name")), cat.calls.filter { it.kind == "update" })
        assertTrue(cat.calls.none { it.kind == "create" })
        val row = store.getById("a")!!
        assertTrue("renamed row must become clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pushDelete() = runTest {
        val store = FakeStore()
        store.upsert(bookmark("a", 45.0, "x", localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = true))
        val cat = FakeCatalog()
        reconciler(store, cat).reconcile("s1", "i1")

        assertEquals(listOf(FakeCatalog.Call("delete", "i1", 45, "")), cat.calls.filter { it.kind == "delete" })
        assertNull("confirmed delete must be hard-removed", store.getById("a"))
    }

    @Test fun pushDeleteNetworkFailureKeepsTombstone() = runTest {
        val store = FakeStore()
        store.upsert(bookmark("a", 45.0, "x", localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = true))
        val cat = FakeCatalog().apply { deleteOk = false }
        reconciler(store, cat).reconcile("s1", "i1")

        val row = store.getById("a")!!
        assertEquals(true, row.deleted)
        assertTrue("tombstone stays dirty for retry", row.localUpdatedAt > row.lastSyncedAt)
    }

    @Test fun pullInsert() = runTest {
        val store = FakeStore()
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 77, "From source", 1234L))))
        reconciler(store, cat).reconcile("s1", "i1")

        val row = store.rows.single()
        assertEquals(77.0, row.positionSec, 0.0001)
        assertEquals("From source", row.title)
        assertEquals(1234L, row.createdAt)
        assertEquals(false, row.deleted)
        assertTrue("source-sourced row is clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pullRemovesCleanRowAbsentFromServer() = runTest {
        val store = FakeStore()
        store.upsert(bookmark("a", 20.0, "stale", localUpdatedAt = 600L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.success(emptyList()))
        reconciler(store, cat).reconcile("s1", "i1")

        assertNull("clean row missing from source must be removed", store.getById("a"))
    }

    @Test fun pullDoesNotClobberDirtyRows() = runTest {
        val store = FakeStore()
        store.upsert(bookmark("create", 20.0, "pending", localUpdatedAt = 900L, lastSyncedAt = 0L))
        store.upsert(bookmark("rename", 50.0, "local title", localUpdatedAt = 900L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 50, "source title", 500L))))
        cat.createOk = false; cat.renameOk = false
        reconciler(store, cat).reconcile("s1", "i1")

        assertNotNull("dirty pending create must survive pull", store.getById("create"))
        assertEquals("dirty local title must NOT be clobbered", "local title", store.getById("rename")!!.title)
    }

    @Test fun listBookmarksNetworkErrorSkipsPullButPushesHappen() = runTest {
        val store = FakeStore()
        store.upsert(bookmark("a", 12.0, "Intro", localUpdatedAt = 800L, lastSyncedAt = 0L))
        store.upsert(bookmark("clean", 99.0, "keep", localUpdatedAt = 600L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.failure(RuntimeException("down")))
        reconciler(store, cat).reconcile("s1", "i1")

        assertEquals(listOf(FakeCatalog.Call("create", "i1", 12, "Intro")), cat.calls.filter { it.kind == "create" })
        assertNotNull("pull skipped: clean row must NOT be removed", store.getById("clean"))
    }

    @Test fun crossItemIsolation() = runTest {
        val store = FakeStore()
        val cat = FakeCatalog(
            listResult = Result.success(listOf(
                CatalogBookmark("OTHER", 10, "other item", 1L),
                CatalogBookmark("i1", 20, "ours", 2L),
            )),
        )
        reconciler(store, cat).reconcile("s1", "i1")

        val rows = store.rows
        assertEquals(1, rows.size)
        assertEquals("ours", rows.single().title)
    }
}
```

- [ ] **Step 4: Delete moved test files from `core:data`**

```bash
rm core/data/src/test/kotlin/com/riffle/core/data/AudiobookBookmarkReconcilerTest.kt
rm core/data/src/test/kotlin/com/riffle/core/data/ProgressSweepTest.kt
rm core/data/src/test/kotlin/com/riffle/core/data/ProgressSweepBookmarkTest.kt
rm core/data/src/test/kotlin/com/riffle/core/data/AnnotationSyncExceptionMappingTest.kt
rm core/data/src/test/kotlin/com/riffle/core/data/AnnotationSyncStatusStoreTest.kt
```

- [ ] **Step 5: Run `core:sync` tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr"
./gradlew :core:sync:test
```
Expected: All tests PASS (9 bookmark + 6 sweep + 4 sweep-bookmark + 4 exception-mapping + status-store tests)

- [ ] **Step 6: Run all JVM tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr"
./gradlew test
```
Expected: All modules BUILD SUCCESSFUL, no red tests.

- [ ] **Step 7: Commit**

```bash
git add core/sync/src/test/ core/data/src/test/kotlin/com/riffle/core/data/
git commit -m "test(sync): move reconciler + sweep tests to core:sync; rewrite bookmark test for SyncableAudiobookBookmark"
```

---

## Task 7: Validate guardrail + write ADR entry

**Files:**
- No new files (validation only) + optional `CONTEXT.md` update

- [ ] **Step 1: Run `checkNoAndroidImports`**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr"
./gradlew checkNoAndroidImports
```
Expected: BUILD SUCCESSFUL — no offenders in `core/sync`.

- [ ] **Step 2: Run full `check`**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr"
./gradlew check
```
Expected: All tasks green (`checkNoServerReferences`, `checkRiffleLogTags`, `checkNoAndroidImports`, tests).

- [ ] **Step 3: Commit guardrail confirmation (no-op if no files changed)**

If `CONTEXT.md` or documentation needs updating to reflect the new module:
```bash
git add CONTEXT.md  # if modified
git commit -m "docs: note core:sync in module map (Phase 4 complete)"
```

Otherwise just note in the PR body that Phase 4 is done and `core:sync` is now guarded.
