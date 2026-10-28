---
name: reference_migration_tests_api25_preexisting_fails
description: "core:database MigrationTest had 7 schema-JSON-drift failures (migrations 6–13), since FIXED; CI now runs them via the 'Database tests (core:database)' connected job"
metadata: 
  node_type: memory
  type: reference
  originSessionId: a6dfcd94-f51d-4558-93bd-8cf88c69d8a3
---

`core/database` `MigrationTest` (instrumented Room migration tests) has **7 failing isolated-step
cases on `origin/main`**: migration6To7, 7To8, 8To9, 9To10, 10To11, 11To12, 12To13. They fail with
`Migration didn't properly handle: reading_positions/servers` and `NOT NULL constraint failed:
library_items.genres`.

**Root cause (corrected 2026-06-09):** this is NOT an API-25/old-SQLite artifact. The committed
*intermediate* schema JSONs are inconsistent with the migrations — e.g. `12.json`/`13.json` list
`servers.username`, but `username` is only added by `MIGRATION_13_14`. `createDatabase(N)` builds
from `N.json`, applies one migration, then validates → mismatch. The migration *chain* is correct
(`migrateFullChain` 1→latest passes, and the final-version schema validates), so real upgrades are
unaffected; only the per-step tests, which assert wrong intermediate snapshots (plus some test-seed
gaps like the genres NOT NULL insert), fail.

**FIXED (2026-06-09, branch `pkmetski/fix-migration-schemas-ci`):** corrected the three drifted
JSONs to match the migration chain — `7.json` (drop reading_positions.localUpdatedAt), `10.json`
(add it), `12.json` (drop servers.username, add library_items.addedAt) — and added the missing
`genres` value to migration8To9's seed. All 31 MigrationTest cases now pass. Per
[[feedback_no_ignoring_failing_tests]] these were fixed, not tolerated. (Was kept out of the #113
bugfix PR; lives in its own commit/branch alongside the CI job below.)

**CI gap (now partly closed):** `ci.yml` historically ran only `assembleDebug`, `lint`,
`./gradlew test` (JVM), and `:core:data:testDebugUnitTest -PintegrationTests` — NO instrumented
tests, which is why this rotted. The same branch adds a `connected-test` job
(reactivecircus/android-emulator-runner, API 30) running `:core:database:connectedDebugAndroidTest`,
so the migration + DAO tests now run in CI. The `:app` instrumented suite (36 files, run locally via
`make harness-test`) is still NOT in CI — a candidate for a follow-up job.
