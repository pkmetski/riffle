# 0049 — Platform-agnostic core boundary

## Status

Accepted — all extraction phases (#550–#556) landed 2026-07-27.

## Context

Riffle started as a monolithic Android app. Over seven phases (issues #550–#557,
PRs #582, #589, #597, #610, #622, #624, #626, #629) the business logic was
progressively extracted into pure-Kotlin modules that carry no Android runtime
dependency. The goal is a codebase where a future KMP target (desktop, iOS, web)
can consume the core as-is, with only the hosting layer (`app`, `core:data`, etc.)
requiring re-implementation per platform.

Each phase extracted one slice:

| Phase | Issue | What moved |
|---|---|---|
| 0 | #550 | Guardrail task (`checkNoAndroidImports`), module scaffold |
| 1 | #551 | `core:models` — pure data models, serialization |
| 2 | #552 | `core:network` — `AbsApiClient`, `NetworkResult`, HTTP plumbing |
| 3 | #553 | `core:sources` — `Source`/`Service` abstractions, source adapters |
| 3b | #554 | `core:catalog` + catalog plugin modules (Chitanka, Gutenberg, Komga) |
| 4 | #555 | `core:sync` — `ProgressSweep`, `ReconcileLocks`, annotation sync status |
| 5a/5b | #555 | `core:database-api` — Room `@Entity`/`@Dao` split (ADR 0048) |
| 6 | #556 | `core:common` — `FileStore`, `EncryptedKeyValueStore`; `core:domain` — `AudioPlayer`; logger audit |

## Decision

### The boundary rule

A module belongs in the **pure-Kotlin core** if and only if all of the following hold:

1. Its production sources import no `android.*`, `androidx.*` (except
   `androidx.annotation`), or `java.util.logging`.
2. Its logic is platform-neutral — it describes *what* the app does, not *how*
   Android does it.
3. A future non-Android host (desktop, iOS, CLI) could consume it unchanged.

Everything that fails these tests stays in an **Android-hosting** module
(`core:data`, `core:database`, `core:database-api`, `core:logging`, `app`).

### Pure-Kotlin core modules

| Module | Contents |
|---|---|
| `core:common` | `Clock`, `FileStore`, `EncryptedKeyValueStore` interfaces — shared contracts for time, file I/O, and secure key-value storage. Android implementations (`SystemClock`, `FilesdirFileStore`, DataStore-backed store) live in `core:data`. |
| `core:models` | Serializable data classes for the ABS API and domain. No business logic. |
| `core:domain` | Domain models (`WebSourceDescriptor`, `AudioPlayer` interface). No Android API references. |
| `core:network` | `AbsApiClient`, `createDefaultHttpClient`, `NetworkResult`. Uses OkHttp + Ktor/kotlinx.serialization — all pure-JVM. |
| `core:sources` | `Source`, `Service`, `Catalog`, `CatalogCapability` abstractions; source adapters (`AbsSourceAdapter`, `KomgaSourceAdapter`); annotation sync targets (`WebDavAnnotationSyncTarget`, `LocalDirectoryTarget`). |
| `core:sync` | `ProgressSweep`, `ReconcileLocks`, `AnnotationSyncStatusStore`, `AudiobookBookmarkReconciler`. Pure reconciliation logic; injected with `Clock` and `EncryptedKeyValueStore` interfaces. |
| `core:catalog` | `Catalog` interface + `CatalogCapability` mixins. |
| `core:catalog-*` | One plugin module per singleton web-source (Chitanka, Gutenberg, Komga). |
| `core:annotations` | _(planned)_ Annotation model and sync wire format. Not yet created. |

### Android-hosting modules

| Module | Why Android |
|---|---|
| `core:data` | Hilt DI wiring, `Context.filesDir`, DataStore, `LocalStore`, repository impls. |
| `core:database` | Room `@Database`, KSP code gen, migration SQL. |
| `core:database-api` | Room `@Entity` / `@Dao` — Room annotations require `androidx.room`. KMP engine swap tracked in ADR 0048. |
| `core:logging` | `AndroidLogger` calls `android.util.Log`; `LogChannel` enum defined here for co-location with the impl. |
| `app` | Compose UI, Readium navigator, ExoPlayer, Hilt component root, navigation. |

### Guardrail tasks

Three Gradle tasks (wired into `check`, run on every CI push) enforce the boundary:

| Task | Enforces |
|---|---|
| `checkNoAndroidImports` | No `android.*` / `androidx.*` (except `androidx.annotation`) / `java.util.logging` in pure-Kotlin core production sources. Detection in `buildSrc/…/AndroidImportLint.kt`. Scanned modules: `core/common`, `core/models`, `core/domain`, `core/network`, `core/sources`, `core/sync`, `core/annotations` (no-ops if directory absent). |
| `checkNoServerReferences` | No `\bServer[A-Z]` identifiers or bare `serverId` in Kotlin files outside the grandfathered allowlist. Enforces the Source/Service taxonomy (ADR 0041). |
| `checkRiffleLogTags` | No `android.util.Log` literals with `RIFFLE_*` tag strings in production sources. All logging goes through `Logger` + `LogChannel`. |

Adding a new pure-Kotlin core module: add its directory path to
`AndroidImportLint.DEFAULT_MODULE_ROOTS` and update the module map in
`CONTEXT.md` and this ADR.

### Harness test coverage

The extraction phases added 9 JVM tests covering previously-untested logic:

- `core:sync` — `ProgressSweepTest`, `ProgressSweepBookmarkTest`,
  `ReconcileLocksTest`, `AnnotationSyncStatusStoreTest`,
  `AudiobookBookmarkReconcilerTest`
- `core:sources` — `AbsSourceAdapterTest`, `KomgaSourceAdapterTest`,
  `WebDavAnnotationSyncTargetTest`, `WebDavAnnotationSyncTargetFactoryTest`

These are net-new coverage, not replacements for existing instrumented tests. An
audit of `app/src/androidTest` found no tests that duplicate the sync
reconciliation or source-adapter logic now covered by these JVM suites. The
`GoldenTraceHarnessTest` remains as the single end-to-end sentinel: it exercises
the full stack (login → library → EPUB open → progress-sync PATCH) to catch
serialization, networking, DB, or reader regressions that unit tests would miss.

## Consequences

**Positive.**
- All sync logic, source adapters, and interface contracts run as fast JVM tests
  with no Android device/emulator required.
- A future KMP port can import `core:*` pure-Kotlin modules directly; only the
  hosting layer needs re-implementation.
- The `checkNoAndroidImports` guardrail catches boundary drift at CI time, before
  Android dependencies can spread into the core.

**Negative / trade-offs.**
- `core:database-api` is still Android-only because Room annotations require
  `androidx.room`. This is the last major blocker for a full KMP port; ADR 0048
  tracks the Room KMP engine swap.
- The catalog plugin modules (`core:catalog-*`) are pure-Kotlin today but are not
  scanned by `checkNoAndroidImports` — they are presentation-adjacent (display
  names, URLs) and less likely to accumulate Android drift. Add them to
  `DEFAULT_MODULE_ROOTS` if a violation is ever found.

## References

ADR 0002 (Android-first KMP-ready architecture), ADR 0041 (Source/Service
taxonomy), ADR 0044 (WebSourceDescriptor), ADR 0045 (source-agnostic progress
peers), ADR 0048 (Room KMP).
