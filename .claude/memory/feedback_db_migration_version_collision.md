---
name: db-migration-version-collision
description: "When rebasing, a DB migration version-number collision (branch and main both add vN) is still a rebase conflict to resolve inline — renumber and continue, don't stop to ask"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: fe767d67-6857-4b37-960f-593e23c66aff
---

When `git rebase origin/main` hits a Room schema/migration collision — branch adds `schemas/.../N.json` + `MIGRATION_(N-1)_N` + `@Database(version=N)`, and main also landed its own vN independently — treat it as a normal conflict and resolve it inline. Do not abort and ask.

**Why:** [[feedback_auto_resolve_trivial_conflicts]] applies — this is mechanical renumbering, not a judgment call. Asking wastes a turn; the resolution recipe is deterministic.

**How to apply:** Renumber the branch's migration to the next free version (N+1 if main took N):
1. `git mv schemas/.../N.json schemas/.../(N+1).json` on the branch side of the conflict
2. Bump `@Database(version=N+1)` in `RiffleDatabase.kt`
3. Rename `MIGRATION_(N-1)_N` companion → `MIGRATION_N_(N+1)`, update its `startVersion`/`endVersion`
4. Update `addMigrations(...)` in `DataModule.kt`
5. Update the test in `MigrationTest.kt` (rename `migrationNToN1` → `migrationN1ToN2`, adjust `createDatabase`/`runMigrationsAndValidate` version args) and its entry in `migrateFullChain`
6. If a later branch commit also touches the schema JSON or the migration list, cascade the renumber through those commits too

Then `git add` + `git rebase --continue`. Only escalate to the user if the *semantic* migration on main overlaps with the branch's (same columns/tables touched in incompatible ways) — pure version-number collisions are mechanical.
