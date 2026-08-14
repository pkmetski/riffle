# ADR 0058 — Room KMP as the KMP-Ready Persistence Engine

**Status:** Accepted 2026-07-26

## Context

Phase 5 of the multi-platform extraction plan (issue #555, ADR 0002) requires moving Riffle's
persistence layer behind an interface so a future KMP target can consume it. As a prerequisite,
we must choose an engine that:

1. Runs on non-Android JVM/Native targets without an Android runtime.
2. Supports the existing SQLite schema and 59 migration versions.
3. Preserves `Flow`-based observation semantics (load-bearing for several repositories and for
   the Phase 4 `core:sync` reconciler).
4. Carries a migration path that does not require rewriting every DAO.

Two candidates were evaluated during a scoped spike: **SQLDelight** (CashApp) and **Room KMP**
(Google, `androidx.room` 2.7+).

### Current state

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

3. **Migration test suite ports unchanged.** All 59 `MIGRATION_N_(N+1)` companion objects in
   `RiffleDatabase` keep their current `SupportSQLiteDatabase` lambda format.
   `MigrationTest.migrateFullChain` continues to run without modification.

4. **Flow emission semantics are preserved.** The Phase 4 spike (core:sync) documented that
   Room's `Flow`-from-`@Query` emission semantics are load-bearing for the reconciler and for
   several flaky-but-correct DAO tests. Room KMP uses the same invalidation-table machinery and
   the same coroutines extension. SQLDelight's coroutines extension uses a different execution
   model (`Dispatchers.IO` offload with manual `Query.addListener`) that requires compensating
   work to match Room's behaviour.

5. **Gradual, low-risk migration.** Phase 5b (this PR) extracts entities and DAOs into
   `core:database-api` with no engine change. Phase 5c swaps the `android.library` plugin in
   `core:database` for `kotlin.multiplatform` and adds Room KMP's `BundledSQLiteDriver` for
   non-Android targets. Each step is independently deployable and independently testable.

6. **Data loss risk is minimised.** The SQLite schema is untouched; only the Kotlin source
   artifact that wraps it changes. SQLDelight would require converting the migration format and
   building new test scaffolding before any row can be verified as preserved.

### Why not SQLDelight

- All 23 DAOs must be rewritten as `.sq` files (SQL-first workflow replacing the annotation DSL).
- The 59 migration objects need a new format; the `MigrationTest` suite needs to be rebuilt from
  scratch.
- SQLDelight's coroutines extension has different `Flow` emission semantics to Room (see point 4
  above); compensating changes would touch the reconciler and several repositories.
- No incremental migration path: all DAOs must be converted before the database can be opened by
  the new engine, making partial roll-out or rollback harder.
- Higher data-loss risk: the format conversion is a one-way gate that cannot be validated
  row-by-row on a real device without completing the full migration.

## Migration plan

### Phase 5b (this PR) — API/impl split

Extract all entities and DAO interfaces into a new `core:database-api` Android library module.
`core:database` becomes the pure implementation: `RiffleDatabase`, migrations, and the KSP
code-generation target. `core:database` re-exports `core:database-api` via `api(...)` so
existing consumers (`core:data`, `app`) require no build-file changes.

```
core:database-api   (android.library)  — @Entity classes, @Dao interfaces, Room annotations
core:database       (android.library)  — RiffleDatabase + migrations, depends on :core:database-api
core:data           (android.library)  — depends on :core:database (transitively gets :core:database-api)
app                 (android.application) — same
```

`core:database-api` uses `android.library` for now because the `@Entity`/`@Dao` annotations
still come from `androidx.room`, which is an Android dependency. Phase 5c replaces that with
Room KMP's JVM variant.

### Phase 5c (follow-up) — Engine swap

1. Upgrade `core:database-api` from `android.library` to `kotlin.multiplatform`. Room KMP
   annotations are available on the JVM target via the KMP variant of `room-runtime`.
2. Add a `jvm()` target and a `BundledSQLiteDriver` dependency for tests and future desktop/CLI
   consumers.
3. Keep `core:database` as the Android host: it adds the Android SQLite driver and the existing
   `MigrationTest` suite, which remains Android-instrumented.
4. Update `core:data`'s `DatabaseModule` to use `BundledSQLiteDriver` on JVM (e.g. for
   integration tests) vs the standard Android SQLite driver on Android.

### Phase 5d (follow-up) — Guardrail

Add a `checkNoDatabaseImplLeak` Gradle task that fails CI if any file outside `core:database`
imports `com.riffle.core.database.RiffleDatabase`. This ensures the engine-swap surface stays
contained to the impl module.

## Consequences

**Positive**

- Non-database modules in `core:data` can compile against DAO interfaces without depending on
  the Room impl. Engine swap in Phase 5c touches only `core:database`.
- All existing tests, migration objects, and schema JSON files are carried over unchanged.
- The project stays on a single persistence library throughout the migration, eliminating a
  parallel dependency tree.

**Negative / open**

- `core:database-api` remains an `android.library` until Phase 5c. It is not yet a true
  KMP module; pure-JVM unit tests cannot instantiate DAOs against a real database without the
  Android room-testing runner.
- Room KMP's JVM target requires `BundledSQLiteDriver` (ships SQLite as a native resource jar).
  This adds ~3 MB to JVM test classpaths and will need an explicit driver selection in
  `DatabaseModule` for non-Android targets.
- `MigrationTest` cannot use the `BundledSQLiteDriver`; it must remain an Android instrumented
  test (using `SupportSQLiteOpenHelper` via `SupportSQLiteDatabase`). Documented in the test
  file.

## References

- Issue: #555 multi-platform Phase 5
- ADR 0002: Android-first KMP-ready architecture
- Phase 4 PR: #624 (core:sync) — documents the Flow-semantics concern
- Room KMP changelog: https://developer.android.com/jetpack/androidx/releases/room (2.7.0+)
