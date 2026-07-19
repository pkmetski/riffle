# multi-platform Phase 1 — pure models & serialization

Design doc for GitHub Issue #551.

## Context

Phase 0 (#550, merged) added `checkNoAndroidImports` and a golden e2e harness test. Phase 1 is the first structural move: carve pure-Kotlin foundation modules out of `core:domain` so downstream phases (#552 net, #553 sources, #554 sync, #555 persistence, #556 platform-service abstractions) have shared primitives and entity types to import against.

## Reality check on the ticket

The ticket was written assuming `core:domain` was Android- and Moshi-entangled. Neither is true today:

- **No Moshi anywhere in the repo.** `grep -rE "com\.squareup\.moshi|@JsonClass"` returns zero hits. Domain is already on `kotlinx.serialization` end-to-end.
- **`core:domain` is already `kotlin.jvm`**, not `com.android.library`. Deps: `kotlinx-coroutines-core`, `jsoup`, `kotlinx-serialization-json`, `javax.inject`. No Android SDK on the classpath.
- **Zero `android.*` / `androidx.*` imports** in `core/domain/src/main`.
- Only 3 files use `jsoup` (JVM-only, non-KMP-consumable): `EpubTextChars.kt`, `EpubCfiTranslator.kt`, `ReadaloudTextQuotes.kt`. All three stay in the residual `core:domain`.

Consequences: the "Data-format risk" section of the ticket doesn't apply. Stored JSON is byte-identical before/after — no on-disk migration.

## Scope

Split `core:domain` (~100 files) into three modules:

- **`core:common`** — primitives with no domain semantics.
- **`core:models`** — pure `@Serializable` data classes and enums representing domain entities.
- **`core:domain`** (residual) — services, resolvers, reconcilers, stores, use-cases, jsoup-using EPUB parsers; anything with behavior, coroutines, or `@Inject`.

Dependency direction: `models → common`, `domain → models → common`. No back-edges.

## Split boundary

### `core:common` — primitives

- Value classes: `BookId`, `SourceId`, `ServiceId`, `AnnotationId`, `LibraryItemId` (create the ones that don't already exist as inline value classes; migrate the ones that do).
- Shared functional types: a small `Result`/`Either`-equivalent sealed type if one is used across modules (audit before adding — don't create speculatively).
- Error taxonomy: sealed class for source/sync failure categories.
- `kotlinx-datetime` re-exposed via `api` so callers get it transitively. **No migration of existing `java.time.*` usage** in this phase.

Dependencies: `kotlinx-datetime`, `kotlinx-serialization-json`.

### `core:models` — domain entities

Pure `@Serializable` data classes and enums, no behavior methods beyond trivial derived accessors:

- `Book`, `Annotation` (+ its enums), `Bookmark`, `AudiobookBookmark`
- Progress variants
- `Source`, `Service`, `LibraryItem`
- `TocEntry`, `AnnotationDeviceMeta`, `AnnotationFileHeader`
- `HighlightColor`, `AppTheme`, `AudiobookTracks` data
- Any pure data type currently in `core:domain` that has no coroutines / no `@Inject` / no jsoup

`AppTheme` — the rule is mechanical: pure `@Serializable` enum → models. Even though it's semantically a preference, the split boundary is by shape, not by domain role.

Dependencies: `core:common`, `kotlinx-serialization-json`.

### `core:domain` (residual)

Everything else. Non-exhaustive: `AnnotationMergeService`, `AudiobookIdentityResolver`, `ReadingSessionController`, `*Store`, `*Repository`, `*Reconciler`, use-cases with `@Inject`, and the 3 jsoup files.

Dependencies (unchanged shape): `kotlinx-coroutines-core`, `jsoup`, `kotlinx-serialization-json`, `javax.inject`, `core:models`, `core:common` (transitive).

### Judgment calls made per file during the move

Some files half-belong in models and half in domain. Example: `AudiobookTimeline` is a data class but has computation helpers. Rule: if `git grep` shows the helpers are called from `core:domain` code, the data class moves to `core:models` and the helpers stay in `core:domain` as top-level extension functions. This is per-file mechanical judgment during the move, not something the design pre-specifies.

## Migration mechanics

**Package strategy:** move to matching new packages — `com.riffle.core.common.*` and `com.riffle.core.models.*`. Do NOT keep the old `com.riffle.core.domain.*` package for moved types, and do NOT create typealias re-export shims. The whole point of Phase 1 is real module boundaries; a shim lets callers stay lazy and defeats the enforcement.

**File moves:** `git mv` per file so blame is preserved for the pure moves. Import-rewrite edits are separate commits from moves so the diff is legible.

**Callsite update:** one PR, mechanical import rewrite via grep+sed / IDE refactor. Kotlin's compiler is the final source of truth for "did we miss anything."

**Commit sequence inside the PR:**

1. Create empty `core:common` module (build file + `settings.gradle.kts` entry + add to `checkNoAndroidImports` enforced set with empty allowlist).
2. Move primitives → `core:common`. Update every import. Green `./gradlew test`.
3. Create empty `core:models` module with `implementation(project(":core:common"))` and empty allowlist entry in `checkNoAndroidImports`.
4. Move data classes → `core:models`. Update every import. Green `./gradlew test`.
5. Ensure `core:domain` declares `implementation(project(":core:models"))` (which transitively brings `core:common`).
6. Run the Phase 0 golden e2e harness test — must stay green.

**Test co-location:** serialization/data-shape tests (`CrossEpubIndexSerializerTest`, `EmbeddedFigureSerializationTest`, `HighlightColorTest`, `ServerTypeTest`, etc.) move with the class they exercise. Logic tests stay in `core:domain`.

## Verification & CI gates

Zero behavior change means verification is proving no regression + proving the new boundaries hold.

Automated gates that must be green in the PR:

1. `./gradlew test` — full JVM suite. Every relocated test passes unchanged.
2. `./gradlew checkNoAndroidImports` — with `core:common` and `core:models` added to the enforced set (empty allowlists). Proves both new modules are pure-Kotlin.
3. `./gradlew checkRiffleLogTags` and `./gradlew checkNoServerReferences` — regression check that pre-existing lints still pass.
4. `./gradlew assembleDebug` — compile-only proof that every caller was updated correctly.
5. Phase 0 golden e2e harness test via `make harness-test` — the explicit Phase 1 success criterion from the ticket.

**No new tests required.** The ticket has no functional surface — it's a code move. AGENTS.md's regression-test requirement applies to bug fixes and new behavior; a module split's assertion-that-would-flip-red-on-revert is "does it compile" plus the golden e2e trace.

**No AVD verification required beyond the harness test.** This is not a reader/UI change per `work-on` §3a — no Readium behavior, no WebView, no reader mode differences. The golden e2e harness test *is* the AVD verification.

**Blast-radius sanity check before opening the PR:** `git diff --stat origin/main` should show ~40 files renamed (git tracks renames when content is unchanged) plus N call-site edits across `app/` and `core:*`. If the stat shows semantic edits to files that were supposed to be pure moves, investigate before pushing.

## Rollout

Single PR, single branch. This is not stackable — the mechanical import rewrites are all-or-nothing per module. Splitting into "move common" and "move models" as separate PRs would leave `main` in a half-migrated state where callers import from three packages for the same conceptual layer; the rebase pain would exceed the review-size savings. One reviewer-sized PR is worth the concentrated diff.

## Non-goals (deferred to later phases)

- Replacing `jsoup` with a pure-Kotlin HTML parser. `core:domain` retains jsoup; the 3 files stay there. Deferred to Phase 3 or later when a KMP target actually needs it.
- Converting `core:common` / `core:models` to KMP modules. Phase 1 leaves them on the `kotlin.jvm` plugin. The KMP plugin swap comes in a later phase when a second target is added.
- Migrating existing `java.time.*` callers in `core:domain` to `kotlinx-datetime`. Only re-exposing the types in `core:common` as available.
- Removing `javax.inject:javax.inject:1` from `core:domain`. Use-cases keep `@Inject`; that dep stays.
- Any changes to `core:database`, `core:network`, `core:data`, or other module boundaries.

## Success criteria (Phase 1 exit)

- `core:common` and `core:models` exist as pure-Kotlin JVM modules with no `android.*` imports.
- `checkNoAndroidImports` includes both modules with empty allowlists and passes.
- Every caller of the moved types imports from the new packages; the old `com.riffle.core.domain.*` locations for moved types no longer exist.
- Full app builds via `./gradlew assembleDebug`.
- Golden e2e harness test from Phase 0 stays green.
- No stored JSON format change (guaranteed by the fact that no serialization framework changes).
