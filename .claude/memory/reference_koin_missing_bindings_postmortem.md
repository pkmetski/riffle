---
name: koin-missing-bindings-postmortem
description: "Hilt→Koin migration (#840) shipped runtime-only missing DI bindings; how they hid, how to find them, and the KoinModuleVerificationTest that pins the graph"
metadata: 
  node_type: memory
  type: reference
  originSessionId: f88639d0-359c-4d78-b05e-b427e30c6e42
  modified: 2026-08-30T22:09:51.312Z
---

The Hilt→Koin migration (PR #840, merged 2026-08-30) converted compile-time DI errors into runtime `InstanceCreationException`s. FIXED in PR #843 (merged 2026-08-30, squash 7c049c1b0): ~16 missing bindings (`AudiobookBundleApi`, `StorytellerBundleApi`/`ProbeApi`, `UiProgressSink`, `PostSweepMaterializer`, `ReaderSyncFactory`, `HighlightsPdfExporter`, `LibraryTabVisibilityObserver`, `RemoteItemFreshness`, `WebSourceItemGate`, `SingletonWebSourceInstaller`, 4 LocalFiles classes, `LocalFilesFolderWatcher` — the last one crashed the PRODUCTION app at startup, invisible to the harness because the test runner boots a plain Application), plus erased-generic `Map<SourceType,*>` collisions (Koin indexes by erased KClass; unqualified generic defs silently override each other — qualify with named()), colliding `List<>` defs inlined into registries, duplicate `Logger` removed. Phone harness went 29 failures → 0 (350 tests).

**Bug shapes (all from this incident):**
1. Concrete `Impl` bound, consumer `get()`s the interface (`single { AudiobookBundleApiImpl(...) }` vs `api: AudiobookBundleApi`).
2. Class never bound at all (Hilt auto-provided `@Inject` classes the migration didn't hand-list).
3. Constructor takes concrete type while Koin binds the interface (the original AddSourceViewModel fix).

**Why CI didn't stop it:** the phone harness DID fail 3× on the PR, but each whack-a-mole fix push cancelled the previous run (`cancel-in-progress`), and the PR was admin-merged while the required phone check was still running (`enforce_admins` off). Post-merge, 20+ failing tests × ~30s crash overhead pushed runs past the 20-min `timeout-minutes` → jobs showed **cancelled** (not failed) with NO report/logcat artifacts. Timeout temporarily bumped to 35 (revert tracked in #845).

**Defenses:** `app/src/test/.../di/KoinModuleVerificationTest.kt` verifies every com.riffle definition's primary-constructor deps against the bound-type index with EXACT name matching (substring matching lets `FooApiImpl` shadow a missing `FooApi`). Blind spot: `single<Interface> { Impl(get()) }` lambdas — reflection can't see them; sweep those offline by grepping `get<T>()` and `param = get()` in the 4 DI module files against bound types.

**How to trace at runtime:** the top-level `InstanceCreationException` names the outermost factory (e.g. LibraryItemsViewModel); the real culprit is the LAST `Caused by: NoDefinitionFoundException` in the chain — fetch it from local `make harness-test` XML results, CI logs truncate it.

Related: [[never-merge-before-checks-green]]
