# 0049 — Platform-agnostic core boundary

## Status

Accepted — updated 2026-07-28 alongside the missing KMP networking, sync, and persistence implementation.

## Context

Riffle started as a monolithic Android app. Across issues #550–#557 and the Phase 2b
follow-up #631, business logic was progressively extracted into Kotlin Multiplatform
modules. Shared source sets carry no Android runtime dependency; platform source sets
provide JVM/Android and iOS implementations where a native engine is required.

Each phase extracted one slice:

| Phase | Issue | What moved |
|---|---|---|
| 0 | #550 | Guardrail task (`checkNoAndroidImports`), module scaffold |
| 1 | #551 | `core:models` — pure data models, serialization |
| 2/2b | #552, #631 | `core:net` — shared clients/DTOs, JVM OkHttp engine, iOS Darwin engine |
| 3 | #553 | `core:sources` — `Source`/`Service` abstractions, source adapters |
| 4 | #554 | `core:sync` — `ProgressSweep`, `ReconcileLocks`, annotation/bookmark reconciliation |
| 5a–5d | #555 | Room KMP database API/implementation, bundled SQLite, import guardrail (ADR 0048) |
| 6 | #556 | `Clock`, `RandomProvider`, `FileStore`, key-value and audio boundaries; logger audit |
| 7 | #557 | Boundary documentation and harness/JVM-test audit |

## Decision

### The boundary rule

A source file belongs in shared `commonMain` if and only if all of the following hold:

1. It imports no platform API (`android.*`, Java-only APIs, Darwin APIs, or platform engines).
2. Its logic is platform-neutral — it describes *what* the app does, not *how*
   Android does it.
3. Every configured KMP target can compile and consume it unchanged.

Platform implementations belong in `androidMain`, `jvmMain`, or `iosMain`. Android composition
and UI remain in hosting modules (`core:data`, `core:logging`, `app`). `core:network` is a
JVM/Android compatibility shim for streaming APIs that intentionally expose `InputStream`.

### Pure-Kotlin core modules

| Module | Contents |
|---|---|
| `core:common` | `Clock`, `RandomProvider`, `FileStore`, `EncryptedKeyValueStore` contracts. JVM system implementations live in `jvmMain`; Android storage implementations live in `core:data`. |
| `core:models` | Serializable data classes for the ABS API and domain. No business logic. |
| `core:domain` | Domain models (`WebSourceDescriptor`, `AudioPlayer` interface). No Android API references. |
| `core:net` | Shared Ktor clients, DTOs, `NetworkResult`, and plugins in `commonMain`; OkHttp in `jvmMain`; Darwin in `iosMain`. |
| `core:sources` | `Source`, `Service`, `Catalog`, `CatalogCapability` abstractions; source adapters (`AbsSourceAdapter`, `KomgaSourceAdapter`); WebDAV annotation sync target (`WebDavAnnotationSyncTarget`). |
| `core:sync` | `ProgressSweep`, `ReconcileLocks`, `AnnotationSyncStatusStore`, `AudiobookBookmarkReconciler`. KMP reconciliation logic using injected `Clock`, `RandomProvider`, and source ports. |
| `core:catalog` | `Catalog` interface + `CatalogCapability` mixins. |
| `core:catalog-*` | One plugin module per singleton web-source (Chitanka, Gutenberg, Komga). |
| `core:annotations` | _(planned)_ Annotation model and sync wire format. Not yet created. |

### Persistence and hosting modules

| Module | Boundary |
|---|---|
| `core:database-api` | KMP Room entity/DAO contracts and the platform-neutral `RiffleDatabaseAccess` surface. |
| `core:database` | KMP Room `@Database`, all historical migrations, bundled SQLite driver, and Android/JVM/iOS factories. |
| `core:network` | JVM/Android shim for EPUB/bundle streaming APIs that expose Java streams. |
| `core:data` | Hilt DI wiring, `Context.filesDir`, DataStore, `LocalStore`, repository impls, `LocalDirectoryTarget`. |
| `core:logging` | `AndroidLogger` calls `android.util.Log`; `LogChannel` enum defined here for co-location with the impl. |
| `app` | Compose UI, Readium navigator, ExoPlayer, Hilt component root, navigation. |

### Guardrail tasks

Five Gradle tasks (wired into `check`, run on every CI push) enforce the boundary:

| Task | Enforces |
|---|---|
| `checkNoAndroidImports` | No Android imports in shared core production sources. Platform-specific KMP source sets and tests are excluded. Scans `core/common`, `core/models`, `core/domain`, `core/net`, `core/network`, `core/sources`, `core/sync`, and the future `core/annotations`. |
| `checkNoOkHttpOutsideCoreNet` | No `okhttp3` imports outside `core/net/src/jvmMain`; shared callers use Ktor. |
| `checkNoDatabaseImplLeak` | No `RiffleDatabase`, Room, or SQLite implementation imports outside `core:database` and `core:database-api`; consumers use database access/DAO contracts. |
| `checkNoServerReferences` | No `\bServer[A-Z]` identifiers or bare `serverId` in Kotlin files outside the grandfathered allowlist. Enforces the Source/Service taxonomy (ADR 0041). |
| `checkRiffleLogTags` | No `android.util.Log` literals with `RIFFLE_*` tag strings in production sources. All logging goes through `Logger` + `LogChannel`. |

Adding a new shared core module: add its directory path to
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
- Shared networking, sources, sync, models, domain, and persistence compile for JVM and iOS.
- Sync logic, source adapters, database contracts, and interface contracts run as fast JVM tests.
- A future host supplies only platform composition, paths, secure storage, logging, and UI.
- The `checkNoAndroidImports` guardrail catches boundary drift at CI time, before
  Android dependencies can spread into the core.

**Negative / trade-offs.**
- Room annotations remain visible in the persistence contract even though Room now publishes KMP
  artifacts; replacing Room would still require adapting DAO annotations.
- Android migration tests remain device tests because they validate upgrades through the shipping
  Android host. A JVM bundled-driver integration test provides an additional non-Android sentinel.
- `core:network` remains a JVM/Android shim until its Java-stream consumers gain a portable stream
  contract.
- The catalog plugin modules (`core:catalog-*`) are pure-Kotlin today but are not
  scanned by `checkNoAndroidImports` — they are presentation-adjacent (display
  names, URLs) and less likely to accumulate Android drift. Add them to
  `DEFAULT_MODULE_ROOTS` if a violation is ever found.

## References

ADR 0002 (Android-first KMP-ready architecture), ADR 0041 (Source/Service
taxonomy), ADR 0044 (WebSourceDescriptor), ADR 0045 (source-agnostic progress
peers), ADR 0048 (Room KMP), issue #631 (KMP networking follow-up).
