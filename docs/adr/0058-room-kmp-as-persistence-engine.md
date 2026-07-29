# ADR 0058 — Room KMP as the KMP-Ready Persistence Engine

**Status:** Accepted 2026-07-26; Phase 5c/5d implementation recorded 2026-07-28

## Context

Phase 5 of the multi-platform extraction plan (issue #555, ADR 0002) requires moving Riffle's
persistence layer behind an interface so a future KMP target can consume it. As a prerequisite,
we must choose an engine that:

1. Runs on non-Android JVM/Native targets without an Android runtime.
2. Supports the existing SQLite schema and its full migration history.
3. Preserves `Flow`-based observation semantics (load-bearing for several repositories and for
   the Phase 4 `core:sync` reconciler).
4. Carries a migration path that does not require rewriting every DAO.

Two candidates were evaluated during a scoped spike: **SQLDelight** (CashApp) and **Room KMP**
(Google, `androidx.room` 2.7+).

### State at the decision

- `core:database` (Android library) owns 23 DAOs, 26 entity classes, and `RiffleDatabase` with
  59 schema versions and a full `migrateFullChain` integration test.
- Every DAO is a Room `@Dao` interface with `@Query`/`@Upsert`/`@Insert`/`@Delete` annotations.
- The project already pins `room = "2.8.4"`, a version that ships as a KMP artifact.

## Decision

Use **Room KMP** (`androidx.room:room-runtime 2.8.4`) as the KMP-ready engine.

## Rationale

### Why Room KMP

1. **Already on the right version.** Room 2.7+ publishes `room-runtime` as a KMP artifact with
   JVM, Android, and Native variants. No version bump is required; the project is already on
   2.8.4.

2. **Zero annotation surface change.** All `@Entity`, `@Dao`, `@Query`, `@Upsert`, `@Insert`,
   `@Transaction`, `@ForeignKey`, `@Index`, `@PrimaryKey` annotations port verbatim. There is no
   SQL rewrite, no schema reformatting, and no DAO refactor.

3. **Migration SQL and coverage are preserved.** The migration callbacks move from
   `SupportSQLiteDatabase` to Room KMP's `SQLiteConnection`, with a small compatibility adapter
   for the parameter binding and forward-only cursor operations used by the historical SQL.
   The Android `MigrationTest.migrateFullChain` still validates every schema transition.

4. **Flow emission semantics are preserved.** The Phase 4 spike (core:sync) documented that
   Room's `Flow`-from-`@Query` emission semantics are load-bearing for the reconciler and for
   several flaky-but-correct DAO tests. Room KMP uses the same invalidation-table machinery and
   the same coroutines extension. SQLDelight's coroutines extension uses a different execution
   model (`Dispatchers.IO` offload with manual `Query.addListener`) that requires compensating
   work to match Room's behaviour.

5. **Gradual, low-risk migration.** Phase 5b extracted entities and DAOs into
   `core:database-api` with no engine change. Phase 5c converts both persistence modules to KMP,
   uses Room's bundled SQLite driver, and preserves Android device migration coverage.

6. **Data loss risk is minimised.** The SQLite schema is untouched; only the Kotlin source
   artifact that wraps it changes. SQLDelight would require converting the migration format and
   building new test scaffolding before any row can be verified as preserved.

### Why not SQLDelight

- All 23 DAOs must be rewritten as `.sq` files (SQL-first workflow replacing the annotation DSL).
- The migration objects need a new format; the `MigrationTest` suite needs to be rebuilt from
  scratch.
- SQLDelight's coroutines extension has different `Flow` emission semantics to Room (see point 4
  above); compensating changes would touch the reconciler and several repositories.
- No incremental migration path: all DAOs must be converted before the database can be opened by
  the new engine, making partial roll-out or rollback harder.
- Higher data-loss risk: the format conversion is a one-way gate that cannot be validated
  row-by-row on a real device without completing the full migration.

## Implementation

### Phase 5b — API/implementation split

Entities and DAO interfaces were extracted into `core:database-api`; `core:database` retained
`RiffleDatabase`, migrations, and KSP code generation.

### Phase 5c — KMP engine

Both `core:database-api` and `core:database` are Kotlin Multiplatform modules with Android, JVM,
iOS x64, iOS arm64, and iOS Simulator arm64 targets:

```
core:database-api   (KMP) — common Room entities/DAOs + RiffleDatabaseAccess
core:database       (KMP) — common @Database/migrations/driver config + platform factories
core:data           (Android host) — Hilt wiring through RiffleDatabaseAccess
app                 (Android host) — no direct database implementation dependency
```

`RiffleDatabaseAccess` is the public database handle. Construction is platform-specific:
Android accepts a `Context`; JVM and iOS accept a database path. Shared builder configuration
registers every migration, installs `BundledSQLiteDriver`, and uses a background query context.

The checked-in schema JSON files remain under `core/database/schemas`. Android device tests add
that directory as a test-asset source through the Android Components API. The source set is
`androidDeviceTest`, matching the Android KMP plugin.

JVM coverage opens a real bundled-driver database, writes and reads through a DAO, and observes a
Room `Flow`. Android retains the DAO and complete migration device-test suite.

### Phase 5d — Guardrail

`checkNoDatabaseImplLeak` fails CI if code outside `core:database` and `core:database-api`
imports the concrete `RiffleDatabase`, `androidx.room`, or `androidx.sqlite` implementation
APIs. Consumers depend on `RiffleDatabaseAccess` and DAO/entity contracts.

## Consequences

**Positive**

- Non-database modules compile against DAO interfaces and `RiffleDatabaseAccess`, without seeing
  the concrete generated database.
- All schema JSON files and migration assertions are retained.
- The shared database compiles for JVM and iOS, and a real JVM database verifies DAO and `Flow`
  behavior without an Android runtime.
- The project stays on a single persistence library throughout the migration, eliminating a
  parallel dependency tree.

**Negative / trade-offs**

- `BundledSQLiteDriver` ships SQLite native resources for every target, increasing artifact size
  relative to Android's framework driver.
- Historical migration callbacks now use the KMP SQLite API. The compatibility adapter is
  deliberately narrow and must grow if a future migration needs a new bind or cursor operation.
- The full upgrade chain remains an Android device test because it validates the shipping host and
  checked-in schema assets; JVM coverage validates current-schema construction and observation.
- SQLite 2.7.0 no longer publishes the iOS x64 variant required by Riffle's target matrix, so the
  bundled driver is pinned to 2.6.2 until that target is deliberately removed or restored upstream.

## References

- Issue: #555 multi-platform Phase 5
- ADR 0002: Android-first KMP-ready architecture
- Phase 4 PR: #624 (core:sync) — documents the Flow-semantics concern
- Room KMP changelog: https://developer.android.com/jetpack/androidx/releases/room (2.7.0+)
