# Design — Annotation sync offline resilience + status UI (issue #77)

**Date:** 2026-06-24
**Issue:** [#77 — feat(annotation-sync): WebDAV offline resilience + sync status UI](https://github.com/plamen-kmetski/riffle/issues/77)
**Builds on:** #75 (per-device-file merge engine), #76 (WebDAV target + global config)
**Companion ADR:** [0036 — Durable offline annotation reconcile + sync status surface](../../adr/0036-durable-offline-annotation-reconcile-and-status.md)

## Goal

Make annotation sync robust to a missing or unreachable WebDAV server and surface its state to the user. Reuse the ADR 0030 dirty-set + WorkManager-sweep pattern already shipped for progress; do not invent a new resilience model.

## What's already in place from #75 + #76

- `AnnotationSyncController` runs the live cycles (`syncOnOpen`, `scheduleDebounce`, `syncOnClose`) and silently swallows any exception inside `pushPending` / `syncOnOpen`.
- `WebDavAnnotationSyncTarget` throws typed `AnnotationSyncException.AuthFailed` / `HttpFailure`.
- `WebDavAnnotationSyncTargetFactory` produces a configured target with bounded per-call timeouts.
- `AnnotationSyncTargetHolder` rebuilds the target whenever the config changes — `targetProvider() == null` means sync is unconfigured.
- `AnnotationSyncSettingsScreen` ships the URL/username/password form, a typed `TestConnectionResult` for **Test connection**, **Save**, and **Disable sync (clear config)**.
- The Settings list row shows a static `"Configured · user@host"` summary derived from `AnnotationSyncConfigStore.observe()`.

Resilience is therefore *partially* there: a failed push naturally retries on the next open/edit because `pushPending` re-reads `getAllForItemIncludingDeleted` each cycle. The gaps are: (a) nothing pushes when the user doesn't reopen the book, (b) failures are invisible to the user, (c) Settings shows neither a Local-only warning when unconfigured nor a real-time sync status when configured.

## Architecture

Four additions, each isolated:

| New artefact | Module | Purpose |
|---|---|---|
| `AnnotationEntity.lastSyncedAt: Long` | `core:database` | Per-row dirty bit: `updatedAt > lastSyncedAt` ⇒ pending. Room migration. |
| `AnnotationSweep` | `core:data` | Push-only sweep: enumerate dirty books across all servers, PUT each, stamp rows. Mirrors `ProgressSweep`. |
| `AnnotationSyncWorker` + `AnnotationSyncScheduler` | `app/sync` | Thin `CoroutineWorker` shell; `NetworkType.CONNECTED`; one-shot coalesced (`KEEP`) + 1-hour periodic safety net. Mirrors `ProgressSyncWorker`/`ProgressSyncScheduler`. |
| `AnnotationSyncStatusStore` | `core:data` | Singleton observable: `lastCycleOutcome` (timestamp + category). Live and sweep paths both report into it. |

The existing live `AnnotationSyncController` is reused unchanged in structure; it gains a `lastSyncedAt` stamp on successful push and reports outcomes into the status store.

## Data model change

**Room migration `42→43`** adds:

```sql
ALTER TABLE annotations ADD COLUMN lastSyncedAt INTEGER NOT NULL DEFAULT 0;
```

Default `0` ⇒ pre-existing rows are dirty until the first sweep stamps them. This is correct: on first run of the upgraded app, the sweep pushes everything once. There is no risk of overwriting cloud state because the W3C per-device-file format already encodes the merge winner per (uuid, updatedAt); a duplicate push of an unchanged set is a no-op for any reader.

Follow the CLAUDE.md migration checklist: bump `@Database` version, write `MIGRATION_42_43`, register in `DataModule`, add `migration42To43()` to `MigrationTest`, extend `migrateFullChain`.

### New DAO methods

```kotlin
@Query("SELECT COUNT(*) FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND updatedAt > lastSyncedAt")
fun observePendingCountForBook(serverId: String, itemId: String): Flow<Int>

@Query("SELECT COUNT(*) FROM annotations WHERE updatedAt > lastSyncedAt")
fun observePendingCountAcrossAll(): Flow<Int>

// One row per dirty (serverId, itemId) for sweep enumeration. The map shape is convenient at the
// callsite (group by serverId once) but a List<Pair<String, String>> is equivalent.
@Query("SELECT DISTINCT serverId, itemId FROM annotations WHERE updatedAt > lastSyncedAt")
suspend fun dirtyServerItems(): List<DirtyServerItem>

@Query("UPDATE annotations SET lastSyncedAt = :syncedAt WHERE id IN (:ids)")
suspend fun markSynced(ids: List<String>, syncedAt: Long)

data class DirtyServerItem(val serverId: String, val itemId: String)
```

## `AnnotationSyncStatusStore`

In-memory singleton.

```kotlin
sealed class CycleOutcome {
    object NeverRun : CycleOutcome()
    data class Success(val atMs: Long) : CycleOutcome()
    sealed class Failed(val atMs: Long) : CycleOutcome() {
        data class Network(val at: Long) : Failed(at)
        data class Auth(val at: Long) : Failed(at)
        data class Tls(val at: Long) : Failed(at)
        data class Server(val at: Long, val code: Int) : Failed(at)
        data class Unknown(val at: Long, val message: String?) : Failed(at)
    }
}

@Singleton
class AnnotationSyncStatusStore @Inject constructor() {
    private val _lastCycleOutcome = MutableStateFlow<CycleOutcome>(CycleOutcome.NeverRun)
    val lastCycleOutcome: StateFlow<CycleOutcome> = _lastCycleOutcome.asStateFlow()

    fun report(outcome: CycleOutcome) { _lastCycleOutcome.value = outcome }
}
```

In-memory only. After cold start `lastCycleOutcome = NeverRun` until the first app-start sweep completes (typically within a second on configured installs). Persisting to DataStore is rejected as overkill: the value is immediately overwritten by the app-start sweep.

`AnnotationSyncException` → `CycleOutcome.Failed.*` mapping is a single extension function used by both the sweep and the live controller, so the two paths classify errors identically.

## `AnnotationSweep`

```kotlin
class AnnotationSweep(
    private val targetProvider: () -> AnnotationSyncTarget?,
    private val annotationDao: AnnotationDao,
    private val deviceIdStore: DeviceIdStore,
    private val serverRepository: ServerRepository,   // for ensureAbsUserId(serverId)
    private val statusStore: AnnotationSyncStatusStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run() {
        val target = targetProvider() ?: return  // unconfigured = silent no-op, no status emit
        try {
            val dirty = annotationDao.dirtyServerItems()
            for ((serverId, itemId) in dirty) {
                val namespace = serverRepository.ensureAbsUserId(serverId) ?: continue
                pushBook(target, serverId, namespace, itemId)
            }
            statusStore.report(CycleOutcome.Success(clock()))
        } catch (e: AnnotationSyncException) {
            statusStore.report(e.toFailed(clock()))   // remaining rows stay dirty
        } catch (e: Exception) {
            statusStore.report(CycleOutcome.Failed.Unknown(clock(), e.message))
        }
    }

    private suspend fun pushBook(target: AnnotationSyncTarget, serverId: String, namespace: String, itemId: String) {
        val rows = annotationDao.getAllForItemIncludingDeleted(serverId, itemId)
        if (rows.isEmpty()) return
        val jsonArray = "[\n" + rows.joinToString(",\n") { AnnotationW3CCodec.annotationEntityToW3C(it) } + "\n]"
        val filename = "annotations-${deviceIdStore.getOrCreate()}.jsonld"
        target.write(namespace, itemId, filename, jsonArray)
        annotationDao.markSynced(rows.map { it.id }, clock())
    }
}
```

- **Push-only.** Merging is per-book lazy on open and handled by `AnnotationSyncController.syncOnOpen`. The sweep does not pull.
- **No open-book mutex.** Unlike `ProgressSweep`, which guards against the cross-device-erase scenario, the annotation pushes are idempotent at the W3C-file granularity: a duplicate PUT of the same logical set is harmless because the receiver merges by (uuid, updatedAt). If the live controller and sweep race on the same book, the worst case is one extra PUT.
- **First failure aborts the cycle.** Once a request fails with `AnnotationSyncException`, the failure is almost always connection-scoped (auth/network/TLS), so attempting subsequent books would just yield identical failures. Remaining dirty rows survive for the next cycle.

## `AnnotationSyncWorker` + `AnnotationSyncScheduler`

Direct mirror of `ProgressSyncWorker` / `ProgressSyncScheduler`. Uses a Hilt `EntryPoint` to fetch `AnnotationSweep` (same pattern as `SweepEntryPoint`).

```kotlin
object AnnotationSyncScheduler {
    private const val UNIQUE_SWEEP = "annotation-sync-sweep"
    private const val UNIQUE_PERIODIC = "annotation-sync-sweep-periodic"
    private val onlineConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun sweepNow(context: Context) { /* enqueueUniqueWork(KEEP) one-shot, exp backoff 30s */ }
    fun ensurePeriodic(context: Context) { /* PeriodicWorkRequestBuilder(1, HOURS), KEEP */ }
}
```

**Triggers wired in `RiffleApplication.kt`:**
- App start → `AnnotationSyncScheduler.sweepNow(this)` (immediately after the existing `ProgressSyncScheduler.sweepNow` call).
- Connectivity regain (offline→online flip) → same call, next to the existing one.
- `AnnotationSyncScheduler.ensurePeriodic(this)` at app start (next to `ProgressSyncScheduler.ensurePeriodic`).
- Also: in the live `AnnotationSyncController.pushPending`'s catch block, enqueue `sweepNow(appContext)` so that a backgrounded-then-killed process still has a pending WorkManager request that fires on connectivity regain regardless of app aliveness.

**Battery cost:** essentially zero on top of progress. The periodic worker wakes for both syncs in the same 1-hour cadence; the annotation pass is one cheap `COUNT` query when nothing is dirty (the common case) and exits immediately.

## Live controller change

Two surgical additions to `AnnotationSyncController.pushPending`:

```kotlin
private suspend fun pushPending(serverId: String, namespace: String, itemId: String) {
    val target = targetProvider() ?: return
    try {
        val rows = annotationDao.getAllForItemIncludingDeleted(serverId, itemId)
        if (rows.isEmpty()) return
        val deviceId = deviceIdStore.getOrCreate()
        val jsonArray = "[\n" + rows.joinToString(",\n") { AnnotationW3CCodec.annotationEntityToW3C(it) } + "\n]"
        target.write(namespace, itemId, "annotations-$deviceId.jsonld", jsonArray)
        annotationDao.markSynced(rows.map { it.id }, System.currentTimeMillis())   // (1) stamp
        statusStore.report(CycleOutcome.Success(System.currentTimeMillis()))       // (2) report success
    } catch (e: AnnotationSyncException) {
        statusStore.report(e.toFailed(System.currentTimeMillis()))
        annotationSyncScheduler.sweepNow(appContext)                                // ensure WorkManager catch-up
    } catch (_: Exception) {
        statusStore.report(CycleOutcome.Failed.Unknown(System.currentTimeMillis(), null))
    }
}
```

`syncOnOpen` gets the same outcome-reporting wrapping (Success on full completion of the read+merge, Failed.* on top-level exception). The per-file `read` failure inside the loop continues to be silently skipped as today — one corrupt file does not poison the cycle.

## Settings UI overhaul

`AnnotationSyncSettingsScreen` becomes state-aware. The ViewModel gains a derived `screenState: StateFlow<AnnotationSyncScreenState>`:

```kotlin
sealed class AnnotationSyncScreenState {
    data class Unconfigured(val form: FormState) : AnnotationSyncScreenState()
    data class Configured(
        val status: StatusBadge,                 // Synced / Pending(count) / Error(category)
        val baseUrl: String,
        val username: String,
        val lastSyncRelative: String?,           // null if NeverRun
        val form: FormState,                     // edit-in-place
    ) : AnnotationSyncScreenState()
}
```

**Unconfigured state.** Card at top of screen:
> ⚠️ **Local only** — Highlights, notes, and bookmarks are stored on this device only and will not sync to other devices. Configure WebDAV below to enable sync.

Form (URL/user/pwd, Test connection, Save) renders below unchanged.

**Configured state.** Status card replaces the warning. The card is **purely informational** — no action buttons. The worker handles all retry automatically; the user only ever needs to act on `Auth` / `Tls` errors (edit form, save) and on `Save` the VM enqueues a `sweepNow()` so the new config is validated immediately.

- Header line by `StatusBadge`:
  - ✓ **Synced via WebDAV** (success colour)
  - ⏳ **Pending — N book(s) unsynced** (amber)
  - ⚠ **Sync error** (error colour) + an error sub-line:
    - `Auth` → "Authentication failed — your credentials may have expired. **[Re-enter credentials]** below; sync will retry automatically once they're saved." (the link scrolls to the form, doesn't auto-clear).
    - `Network` → (header reads **Pending**, not error) body: "Couldn't reach the server. Will retry automatically when connectivity returns."
    - `Tls` → "TLS error — the server's certificate could not be verified. Update the URL below; sync will retry automatically once saved."
    - `Server(code)` → "Server returned HTTP $code. Will retry automatically."
    - `Unknown` → "Sync failed. Will retry automatically."
- Identity line: `username@shortHost · /dav/path`
- Last sync: relative ("5 min ago", "just now") from `CycleOutcome.atMs`, or "Never since app start" for `NeverRun`.

Form remains below the status card so the user can edit credentials/URL in place. `AnnotationSyncSettingsViewModel.onSave` calls `AnnotationSyncScheduler.sweepNow(context)` after persisting the new config so the user sees the new state without waiting for the next natural trigger. The existing `OutlinedButton("Disable sync (clear config)")` at the bottom of the form survives unchanged.

**No "Sync now" button.** Every retry path the user could invoke is already automatic: connectivity regain enqueues a sweep, app start enqueues a sweep, the live `pushPending` failure-catch enqueues a sweep, saving new credentials enqueues a sweep, and the 1-hour periodic backs it all up. A manual button would be theatre — its only substantive case (validate-after-Save) is handled by the `onSave` enqueue plus the existing **Test connection** action.

### Settings list-row at-a-glance status

The text-only summary that #76 ships is too low-contrast for a glance — "synced just now" and "sync error" carry identical visual weight, so a user scrolling Settings won't notice that sync has stopped working. The row is promoted to a status-bearing surface:

- **Leading badge** (28dp circle on the left of the row) coloured to the state:
  - ✓ green — Synced
  - ⏳ amber — Pending
  - ⚠ red — Sync error
  - ○ grey — Local only (unconfigured)
- **Sub-line tinted** to match for non-OK states (amber/red), neutral otherwise.
- **Trailing chevron** preserved (the row is still tappable to drill into the WebDAV config screen).

This keeps the at-a-glance signal in the main Settings surface where the user already is, and avoids a separate "status" row duplicating the WebDAV-config row. The status conceptually attaches to *annotation sync*, not the WebDAV config — but since WebDAV is the only sync target, one row is enough; if a second target ever lands, the row repurposes to per-target without restructuring.

`SettingsViewModel.annotationSyncSummary: StateFlow<String?>` is replaced by a richer `annotationSyncRow: StateFlow<AnnotationSyncRowState>`:

```kotlin
data class AnnotationSyncRowState(
    val badge: Badge,                     // Local / Synced / Pending / Error
    val headline: String,                 // always "WebDAV" (or "Annotation sync" if we want a softer label)
    val sub: String,                      // state-specific one-liner
    val subTone: Tone,                    // Normal / Pending / Error
) {
    enum class Badge { Local, Synced, Pending, Error }
    enum class Tone { Normal, Pending, Error }
}
```

State → row mapping:

| State | Badge | Sub | Sub tone |
|---|---|---|---|
| Unconfigured | Local (○ grey) | "Not configured · tap to set up a WebDAV server" | Normal |
| Synced, no pending | Synced (✓ green) | "Synced · `user@host` · just now" | Normal |
| Pending (offline or transient) | Pending (⏳ amber) | "N book(s) pending · will sync when online" | Pending |
| Auth failed | Error (⚠ red) | "Authentication failed · tap to re-enter credentials" | Error |
| TLS error | Error (⚠ red) | "TLS error · tap to check server URL" | Error |
| Server error | Error (⚠ red) | "Server error (HTTP N) · will retry automatically" | Error |
| Unknown failure | Error (⚠ red) | "Sync failed · will retry automatically" | Error |

The row is derived from `combine(configStore.observe(), statusStore.lastCycleOutcome, annotationDao.observePendingCountAcrossAll())` in `SettingsViewModel`, so the badge tracks live state without any callback wiring.

## Reader chrome indicator

A small icon in the reader's existing top action row (next to the bookmark/menu icons), **visible only when state ≠ Synced** for the currently open book. Specifically:

- `pendingCountForBook > 0` → amber up-arrow-with-clock icon ("changes pending").
- `lastCycleOutcome is Failed.Auth | Failed.Tls` → red shield ("auth/TLS error").
- `lastCycleOutcome is Failed.Network` and pending > 0 → grey cloud-off ("offline, will retry").
- Synced / no pending → no icon. A permanent green tick is noise in the reader.

Tapping the icon opens a small popup with the same status sub-line Settings shows, plus a **Settings →** affordance.

## Failure-isolation guarantees ↔ acceptance criteria

| Acceptance criterion | How it's met |
|---|---|
| Edits while offline push automatically on next cycle | `lastSyncedAt < updatedAt` survives across cycles; `AnnotationSyncScheduler.sweepNow` enqueued at app start, on connectivity regain (incl. process-dead via WorkManager constraint), on live-path failure, and via the 1-hour periodic. |
| Failed cycle leaves Room intact | Sweep is push-only; read pass already skips per-file errors; no DAO write occurs unless the network write succeeded. |
| No conflict dialog ever | Already true — merge is silent last-write-wins per (uuid, updatedAt). |
| Status indicator accurate | `pendingCount` is a real-time DAO Flow; `lastCycleOutcome` reflects the most recent run; live + sweep paths report through the same store. |
| Settings shows Local-only warning when unconfigured | Driven by `configStore.observe() == null` (top-level state branch). |
| Differentiates auth/network/TLS | Already typed in `AnnotationSyncException`; classified once in `Exception.toFailed(now)` used by both paths. |
| Tests cover offline→reconnect, failed-cycle isolation, status transitions | See Testing. |

## Testing

| Test | Module | What it proves |
|---|---|---|
| `AnnotationSweepTest` (JVM) | `core:data` | Fake `AnnotationSyncTarget` with switchable behaviour (success, each `AnnotationSyncException` subtype). Asserts: dirty rows stamped only on success; status store sees correct `CycleOutcome`; unconfigured target is a silent no-op; first-failure abort leaves remaining rows dirty. |
| `MigrationTest.migration42To43` | `core:database` | `lastSyncedAt` column added with default 0; existing rows preserved; chained into `migrateFullChain`. |
| `AnnotationSyncControllerLifecycleTest` (extended) | `core:data` | `pushPending` stamps `lastSyncedAt` on success and skips stamp on failure; reports the right `CycleOutcome` to a fake status store; enqueues a sweep on failure (verified via a `FakeSchedulerEnqueueRecord`). |
| `AnnotationSyncStatusStoreTest` (JVM) | `core:data` | Trivial — emissions on `report(...)` and `setRunning(...)`. |
| `AnnotationSyncSettingsViewModelTest` (extended) | `app` | `screenState` correctly derives `Unconfigured` / `Configured(Synced)` / `Configured(Pending)` / `Configured(Failed.*)` from the two stores; `onSave` enqueues a sweep. |
| `SettingsViewModelTest` (extended) | `app` | `annotationSyncRow` state (badge + headline + sub + tone) matches each combined `(config, lastCycleOutcome, pendingCount)` input. |

No new instrumentation tests required; the worker shell is exercised end-to-end by the sweep's own JVM test (the worker is ~10 lines of WorkManager glue).

## Out of scope

- A "Test connection" round-trip from the status card. Existing **Test connection** in the form suffices.
- Per-server WebDAV configs (the issue continues to assume one global config — ADR 0035).
- Surfacing per-row stamps in the annotation list ("synced 5 min ago" per highlight).
- Read-pass sweep on connectivity regain (background pull). Reads happen lazily on book open; a pre-fetch would burn battery for no observable benefit.

## File touch list (estimate)

**New:**
- `core/database/src/main/kotlin/com/riffle/core/database/AnnotationDao.kt` — new methods
- `core/database/src/main/kotlin/com/riffle/core/database/AnnotationEntity.kt` — `lastSyncedAt` column
- `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt` — version bump + new migration
- `core/database/schemas/com.riffle.core.database.RiffleDatabase/<43>.json` — generated by KSP
- `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt` — new `migration42To43`
- `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSweep.kt`
- `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSyncStatusStore.kt`
- `core/data/src/test/kotlin/com/riffle/core/data/AnnotationSweepTest.kt`
- `core/data/src/test/kotlin/com/riffle/core/data/AnnotationSyncStatusStoreTest.kt`
- `app/src/main/kotlin/com/riffle/app/sync/AnnotationSyncWorker.kt`
- `app/src/main/kotlin/com/riffle/app/sync/AnnotationSyncScheduler.kt`
- `docs/adr/0036-durable-offline-annotation-reconcile-and-status.md`

**Modified:**
- `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSyncController.kt` — stamp + status report + sweep-enqueue
- `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt` — provide `AnnotationSweep`, `AnnotationSyncStatusStore`
- `app/src/main/kotlin/com/riffle/app/RiffleApplication.kt` — three sweep-trigger callsites
- `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt` — `annotationSyncRow` derived state (replaces `annotationSyncSummary`)
- `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt` — row gains leading colored badge + colored sub-line + trailing chevron; preserves drill-in behaviour
- `app/src/main/kotlin/com/riffle/app/feature/settings/annotationsync/AnnotationSyncSettingsViewModel.kt` — `screenState` derivation
- `app/src/main/kotlin/com/riffle/app/feature/settings/annotationsync/AnnotationSyncSettingsScreen.kt` — Local-only card, status card, Sync now button
- Reader screen (chrome top row) — new conditional icon + popup
- Reader VM — expose `annotationSyncIndicator: StateFlow<IndicatorState?>`
