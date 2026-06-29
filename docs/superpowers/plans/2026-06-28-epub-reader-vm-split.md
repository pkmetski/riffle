# EpubReaderViewModel Split — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `EpubReaderViewModel` (2,785 LOC, ~38 deps) into a thin assembler that **holds** orchestrators rather than **being** them, satisfying issue #303 in a single PR on branch `pkmetski/montevideo-303`.

**Architecture:** Lift orthogonal concerns out of the VM into 7 collaborators (`PositionOrchestrator`, `ReadaloudSession`, `AnnotationSession`, `FormattingSession`, `BookmarksController`, `SearchController`, `VolumeKeyDispatcher`, `WakeLockController`) and reuse the existing `ProgressReconciler` from #302. The VM becomes flow-composition + event forwarding only. Each orchestrator is unit-tested against the existing `FakeReaderPresenter` from #300; three historical regressions get orchestrator-level tests.

**Tech Stack:** Kotlin, Coroutines/StateFlow, Hilt, JUnit4, kotlinx-coroutines-test, Turbine, Robolectric where unavoidable. No Readium or WebView imports inside orchestrators (the presenter seam absorbs them per #300).

## Global Constraints

- VM final size **≤ 500 LOC**, **≤ 10 constructor params** (issue #303 acceptance criteria).
- No business rule lives in the VM body — only flow composition and event forwarding.
- Orchestrators **MUST NOT** import `org.readium.*`, `android.webkit.*`, or `com.riffle.app.feature.reader.ContinuousReaderView` (per `ReaderPresenter.kt:11`).
- Orchestrators run on the VM's `viewModelScope` injected at construction time (`CoroutineScope` constructor param) — **never** capture `viewModelScope` from within the orchestrator class. ProgressFlush survives teardown via the existing `progressFlushScope` (memory: `reference_progress_flush_scope_teardown.md`).
- `book_formatting_preferences` stays per-device (no `serverId` scoping — memory: `project_formatting_prefs_per_device.md`).
- `ScrollBoundaryNavigationContainer` is authoritative — orchestrators **consume** its events, never edit it (memory: `feedback_scroll_boundary_container_authority.md`).
- All three reader modes must keep working (paginated/vertical/continuous) per `AGENTS.md`.
- Tests run via `./gradlew test` (not module-specific `:testDebugUnitTest`) before claiming green (memory: `feedback_gradle_test_command.md`).
- `JAVA_HOME` for gradle: `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (memory: `reference_java_home_android_studio.md`).
- Harness tests run via `make harness-test` and `make harness-test-tablet` — **never** raw `./gradlew :app:connectedDebugAndroidTest` (per `AGENTS.md`).
- Commit after each task; do not push (memory: `feedback_no_push_without_permission.md`).
- Commit messages: no Claude co-author trailer (memory: `feedback_no_claude_coauthor.md`).
- PR title uses Conventional Commits (memory: `feedback_pr_title_convention.md`): `refactor(reader): split EpubReaderViewModel into orchestrators (#303)`.

---

## File Structure

**New directories:**
- `app/src/main/kotlin/com/riffle/app/feature/reader/session/` — long-lived stateful sessions tied to a single book (Position, Readaloud, Annotation, Formatting).
- `app/src/main/kotlin/com/riffle/app/feature/reader/controllers/` — leaf controllers (Bookmarks, Search, VolumeKeys, WakeLock).

**New production files (each one focused, ~150–600 LOC):**
| File | Responsibility |
|---|---|
| `session/PositionOrchestrator.kt` | Current Locator stream; subscribes to `ReaderPresenter.positionEvents`; emits canonical position; sole owner of `CanonicalPositionTranslator` calls. |
| `session/ReadaloudSession.kt` | Readaloud audio state machine + highlight pipeline + Storyteller-bundle/ABS-audio fallback. Consumes `presenter.pageLoadEvents` for decoration re-anchor. |
| `session/AnnotationSession.kt` | Decoration list, sync banner, conflict resolution. Owns `AnnotationSyncController` + `AnnotationStatusStore` integration. |
| `session/FormattingSession.kt` | `formattingPreferencesStore` + `bookFormattingPreferencesStore` aggregation, `auto` theme schedule, AutoScroll state, `applyTypography` calls. |
| `controllers/BookmarksController.kt` | Bookmark toggle/rename/delete + page-bookmarked detection. Carved out of AnnotationSession. |
| `controllers/SearchController.kt` | Search execution, debounce, results, navigation channel. |
| `controllers/VolumeKeyDispatcher.kt` | `volumeKeyNavigationEnabled` + `invertVolumeKeys` config; forwards events. |
| `controllers/WakeLockController.kt` | `keepScreenOn` derived flow (prefs + AutoScroll running). |

**New test files (mirror prod tree under `app/src/test/`):**
- `session/PositionOrchestratorTest.kt`
- `session/ReadaloudSessionTest.kt`
- `session/AnnotationSessionTest.kt`
- `session/FormattingSessionTest.kt`
- `controllers/BookmarksControllerTest.kt`
- `controllers/SearchControllerTest.kt`
- `controllers/VolumeKeyDispatcherTest.kt`
- `controllers/WakeLockControllerTest.kt`
- `session/regressions/ContinuousAnnotationFocusReflowRaceTest.kt` — historical bug 1
- `session/regressions/ReadaloudHighlightRotationReflowTest.kt` — historical bug 2
- `session/regressions/ProgressFlushScopeTeardownTest.kt` — historical bug 3

**Modified files:**
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` — reduce from 2,785 → ≤500 LOC.
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` — rewire any `viewModel.x` calls that move to an orchestrator behind delegation methods on the VM (keep screen public surface stable).
- `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt` — add `@Provides` bindings for the new types (or co-locate in a new `ReaderModule.kt`; see Task 9).

---

## Sequencing Rationale

The cutover follows the issue's recommended order, lightest-coupled first, so that each commit is independently buildable and `./gradlew :app:testDebugUnitTest` passes after every commit (gives us reliable bisect targets if the final integration breaks something):

1. **Scaffolding** — Hilt module shell + CoroutineScope injection pattern.
2. **FormattingSession** — smallest blast radius (per issue + memory `project_reader_settings_reorg.md`).
3. **BookmarksController** + **SearchController** — leaf concerns.
4. **VolumeKeyDispatcher** + **WakeLockController** — pure passthrough leaves.
5. **PositionOrchestrator** — touches the `onPositionChanged` hot path.
6. **AnnotationSession** — depends on Position.
7. **ReadaloudSession** — most coupled, most fragile (memory: many highlight regressions).
8. **Historical-regression tests** — three orchestrator-level tests.
9. **VM final audit + acceptance verification** — confirm ≤500 LOC, ≤10 deps, run harness, AVD verification across all three reader modes.

Every extraction follows the same micro-pattern (the "lift pattern" used throughout):
1. Write failing unit test for the new orchestrator (TDD).
2. Create the orchestrator class with the failing interface.
3. Make the unit test pass.
4. Wire the orchestrator into the VM constructor; delete the lifted code from the VM.
5. Run `./gradlew :app:testDebugUnitTest` — must stay green.
6. Commit.

---

## Task 0: Branch hygiene + plan baseline

**Files:** none.

- [ ] **Step 0.1:** Confirm branch.
```bash
git rev-parse --abbrev-ref HEAD  # expect: pkmetski/montevideo-303
git status                       # expect: clean (plan file already committed if you saved it)
```

- [ ] **Step 0.2:** Commit the plan if not already committed.
```bash
git add docs/superpowers/plans/2026-06-28-epub-reader-vm-split.md
git commit -m "docs: add implementation plan for VM split (#303)"
```

- [ ] **Step 0.3:** Sanity test baseline.
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL. Record the test count for sanity-check at end.

---

## Task 1: Scaffolding — orchestrator CoroutineScope pattern + module shell

**Goal:** Establish the pattern every orchestrator follows so subsequent tasks are mechanical lifts.

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/session/OrchestratorScope.kt`
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/di/ReaderModule.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/session/OrchestratorScopeTest.kt`

**Interfaces produced:**
```kotlin
// OrchestratorScope.kt
package com.riffle.app.feature.reader.session

import kotlinx.coroutines.CoroutineScope

/**
 * Marker typealias making it explicit at call sites that an orchestrator does NOT capture
 * viewModelScope itself — the VM injects its own scope so teardown is deterministic.
 */
internal typealias OrchestratorScope = CoroutineScope
```

- [ ] **Step 1.1: Write the failing test.**
```kotlin
// app/src/test/kotlin/com/riffle/app/feature/reader/session/OrchestratorScopeTest.kt
package com.riffle.app.feature.reader.session

import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import kotlin.test.assertEquals

class OrchestratorScopeTest {
    @Test fun `is a typealias for CoroutineScope`() {
        val scope: OrchestratorScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        assertEquals(CoroutineScope::class, (scope as Any)::class.supertypes.first().classifier ?: scope::class)
    }
}
```

- [ ] **Step 1.2: Run; expect compile failure** (`OrchestratorScope` doesn't exist).
```bash
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.session.OrchestratorScopeTest"
```

- [ ] **Step 1.3: Create the file** (see Interfaces above).

- [ ] **Step 1.4: Re-run.** Expect: green.

- [ ] **Step 1.5: Create `ReaderModule.kt` shell** (empty `@Module` with `@InstallIn(ViewModelComponent::class)` — bindings added per task). This separates reader DI from `DataModule.kt` so subsequent diffs are tight.
```kotlin
package com.riffle.app.feature.reader.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
internal object ReaderModule
```

- [ ] **Step 1.6: Commit.**
```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/session/OrchestratorScope.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/di/ReaderModule.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/session/OrchestratorScopeTest.kt
git commit -m "refactor(reader): add orchestrator scaffolding for VM split (#303)"
```

---

## Task 2: Extract FormattingSession

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/session/FormattingSession.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt:293-405,567-613,648-670,873-889,2087-2115`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/session/FormattingSessionTest.kt`

**Interfaces produced (consumed by VM in subsequent tasks):**
```kotlin
internal class FormattingSession(
    private val scope: OrchestratorScope,
    private val timeProvider: TimeProvider,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val bookFormattingPreferencesStore: BookFormattingPreferencesStore,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val autoScrollController: AutoScrollController,
) {
    val effectiveFormattingPreferences: StateFlow<FormattingPreferences>
    val hasBookOverrides: StateFlow<Boolean>
    val formattingPreferencesReady: StateFlow<Boolean>
    val autoScrollState: StateFlow<AutoScrollState>
    val autoScrollScrollDeltas: SharedFlow<Float>

    fun bindToBook(bookId: Long)
    fun updateFormatting(prefs: FormattingPreferences)
    fun resetToGlobalDefaults()
    fun setAutoScrollPaused(paused: Boolean)
    fun startAutoScroll()
    fun stopAutoScroll()
    fun nudgeAutoScroll(deltaPx: Float)
    fun pauseAutoScroll()
    fun resumeAutoScrollIfPaused()
    fun reachedEndOfBookForAutoScroll()
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun onBookClosed()
}
```

- [ ] **Step 2.1: Write FormattingSessionTest scaffolding** with 12 tests covering:
  - `effectiveFormattingPreferences emits global when no book overrides`
  - `effectiveFormattingPreferences merges book overrides on top`
  - `updateFormatting persists to bookFormattingPreferencesStore`
  - `resetToGlobalDefaults clears book overrides`
  - `auto theme switches at the configured boundary tick`
  - `auto-scroll stops when playback isPlaying transitions to true`
  - `setAutoScrollPaused pauses then resumeAutoScrollIfPaused resumes`
  - `formattingPreferencesReady gates emission until prefs load`
  - `hasBookOverrides true when any book pref set`
  - `bindToBook re-issues the override stream`
  - `onBookClosed cancels theme schedule subscription`
  - `WPM updates push to autoScrollController.layoutContext`

Use the canonical fake-store pattern from `FormattingPreferencesStoreTest.kt` (find with `rg 'class FormattingPreferences.*StoreTest'`). Each test uses `runTest` + Turbine.

- [ ] **Step 2.2: Run; expect compile failure.**
```bash
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.session.FormattingSessionTest"
```

- [ ] **Step 2.3: Create `FormattingSession.kt`** by lifting code verbatim from these VM ranges:
  - VM L380–L405 (`_bookOverrides`, `_hasBookOverrides`, `effectiveFormattingPreferences`)
  - VM L293–L356 (AutoScroll integration)
  - VM L412–L412 (`_formattingPreferencesReady`)
  - VM L567–L613 (prefs combine chains — `loadFormattingPreferences()`)
  - VM L648–L670 (theme schedule tick loop)
  - VM L873–L889 (book-open prefs reload)
  - VM L2087–L2103 (`updateFormatting()`, `resetToGlobalDefaults()`)
  - VM L617–L630 (auto-scroll stop on readaloud — adapt as `onPlaybackStateChanged`)

Translate `viewModelScope.launch { … }` to `scope.launch { … }`.

- [ ] **Step 2.4: Make tests pass.** Iterate; each red test points at a lift error.

- [ ] **Step 2.5: Wire FormattingSession into VM.**
  - Add `private val formatting: FormattingSession` to VM constructor (Hilt-provided via `ReaderModule.kt` — add `@Provides @ViewModelScoped fun provideFormattingSession(scope: CoroutineScope = …)` *or* construct inline in the VM init block using `viewModelScope`).
  - **Pattern for Hilt + viewModelScope:** orchestrators are constructed inside the VM `init` block (or `lazy`) using `viewModelScope`; Hilt provides the *factory*, not the instance. Define a `FormattingSession.Factory` interface that takes `scope: CoroutineScope` and have Hilt provide the factory. Example:
    ```kotlin
    internal class FormattingSession @AssistedInject constructor(
        @Assisted private val scope: CoroutineScope,
        … other deps …
    ) {
        @AssistedFactory
        internal interface Factory {
            fun create(scope: CoroutineScope): FormattingSession
        }
    }
    ```
  - VM holds `private val formatting = formattingSessionFactory.create(viewModelScope)`.
  - VM exposes (delegations, no logic):
    ```kotlin
    val effectiveFormattingPreferences = formatting.effectiveFormattingPreferences
    val hasBookOverrides = formatting.hasBookOverrides
    val formattingPreferencesReady = formatting.formattingPreferencesReady
    val autoScrollState = formatting.autoScrollState
    val autoScrollScrollDeltas = formatting.autoScrollScrollDeltas
    fun updateFormatting(p: FormattingPreferences) = formatting.updateFormatting(p)
    fun resetToGlobalDefaults() = formatting.resetToGlobalDefaults()
    fun setAutoScrollPaused(paused: Boolean) = formatting.setAutoScrollPaused(paused)
    // …etc.
    ```
  - Delete all lifted code from the VM (the line ranges in Step 2.3).
  - Reduce VM constructor params by removing: `formattingPreferencesStore`, `bookFormattingPreferencesStore`, `wakeLockPreferencesStore` (moved to WakeLockController in Task 5), `listeningPreferencesStore`, `autoScrollController`, `timeProvider` (still kept if other concerns use it; verify).

- [ ] **Step 2.6: Add Hilt binding in `ReaderModule.kt`.**
```kotlin
@Module
@InstallIn(ViewModelComponent::class)
internal interface ReaderAssistedModule
// (AssistedInject doesn't need explicit @Provides — the @AssistedFactory is auto-wired.)
```
Note: AssistedInject auto-generates the factory binding; only ensure `@AssistedFactory` is `internal` and visible from the VM package.

- [ ] **Step 2.7: Run the full app unit-test suite.**
```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL, test count ≥ baseline. Fix any failures (typically: tests that mocked the removed VM params now fail at construction — update them to mock `FormattingSession.Factory` instead and have it return a fake).

- [ ] **Step 2.8: Commit.**
```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/session/FormattingSession.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/session/FormattingSessionTest.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/di/ReaderModule.kt
git commit -m "refactor(reader): extract FormattingSession (#303)"
```

---

## Task 3: Extract BookmarksController and SearchController

These two share the task because each is small (~100 LOC each) and they don't interact with each other.

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/controllers/BookmarksController.kt`
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/controllers/SearchController.kt`
- Modify: `EpubReaderViewModel.kt` — remove L1532–L1575 (toggle/rename), L1727–L1740 (page-bookmarked), L857–L870 (search debounce), L1972–L2045 (search ops), L460–L470 (state flows).
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/controllers/BookmarksControllerTest.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/controllers/SearchControllerTest.kt`

**Interfaces produced:**
```kotlin
internal class BookmarksController @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val annotationStore: AnnotationStore,
    private val timeProvider: TimeProvider,
) {
    val bookmarkPositions: StateFlow<List<BookmarkPosition>>
    val isCurrentPageBookmarked: StateFlow<Boolean>

    fun bind(serverId: String, namespace: String, itemId: String,
             currentLocator: StateFlow<Locator?>)
    fun toggleBookmark()
    fun renameBookmark(annotationId: String, title: String)
    @AssistedFactory interface Factory { fun create(scope: CoroutineScope): BookmarksController }
}

internal class SearchController @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
) {
    val isSearchActive: StateFlow<Boolean>
    val searchQuery: StateFlow<String>
    val searchResults: StateFlow<List<SearchResult>>
    val currentSearchIndex: StateFlow<Int>
    val searchNavigationChannel: SharedFlow<Locator>

    fun bind(publication: Publication?)
    fun openSearch()
    fun closeSearch()
    fun onSearchQueryChanged(q: String)
    fun nextSearchResult()
    fun prevSearchResult()
    @AssistedFactory interface Factory { fun create(scope: CoroutineScope): SearchController }
}
```

- [ ] **Step 3.1: Write `BookmarksControllerTest` with 6 tests:**
  - `toggleBookmark on un-bookmarked page creates a Bookmark annotation`
  - `toggleBookmark on bookmarked page deletes the existing bookmark`
  - `isCurrentPageBookmarked reflects bookmark presence at current href+progression`
  - `bookmarkPositions reactively follows annotationStore.observeBookmarks`
  - `renameBookmark updates and schedules sync debounce`
  - `bind clears state from previous book`

- [ ] **Step 3.2: Lift VM L1532–L1575 + L1639–L1682 + L1727–L1740 into `BookmarksController`.** Same `scope.launch` adaptation as Task 2.

- [ ] **Step 3.3: Make tests pass.**

- [ ] **Step 3.4: Write `SearchControllerTest` with 5 tests:**
  - `onSearchQueryChanged debounces at 300ms before performSearch`
  - `performSearch populates searchResults from publication.search`
  - `nextSearchResult cycles index and emits to channel`
  - `prevSearchResult cycles index backward and emits to channel`
  - `closeSearch clears query, results, and index`

- [ ] **Step 3.5: Lift VM L857–L870 + L1972–L2045 into `SearchController`.**

- [ ] **Step 3.6: Make tests pass.**

- [ ] **Step 3.7: Wire both into VM** (same AssistedInject factory pattern as Task 2). Expose pass-through `val`s and methods. Delete lifted code from VM.

- [ ] **Step 3.8: Drop VM constructor params no longer needed.** Verify `annotationStore` still needed by AnnotationSession (Task 7) — keep for now if so, but remove if BookmarksController is the only consumer left after Task 7.

- [ ] **Step 3.9: Run app unit tests.**
```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```

- [ ] **Step 3.10: Commit.**
```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/controllers/BookmarksController.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/controllers/SearchController.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/controllers/BookmarksControllerTest.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/controllers/SearchControllerTest.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "refactor(reader): extract BookmarksController and SearchController (#303)"
```

---

## Task 4: Extract VolumeKeyDispatcher

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/controllers/VolumeKeyDispatcher.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/controllers/VolumeKeyDispatcherTest.kt`
- Modify: VM L358–L362, L376, plus L2110–L2115 (`setVolumeKeyNavigationEnabled`, `setInvertVolumeKeys`).

**Interface produced:**
```kotlin
internal class VolumeKeyDispatcher(
    private val volumeKeyPreferencesStore: VolumeKeyPreferencesStore,
    private val volumeNavigationController: VolumeNavigationController,
) {
    val volumeKeyNavigationEnabled: StateFlow<Boolean>
    val invertVolumeKeys: StateFlow<Boolean>
    val volumeNavEvents: SharedFlow<VolumeNavEvent>

    suspend fun setVolumeKeyNavigationEnabled(enabled: Boolean)
    suspend fun setInvertVolumeKeys(invert: Boolean)
}
```

- [ ] **Step 4.1: Write tests (3):**
  - `volumeKeyNavigationEnabled mirrors store`
  - `invertVolumeKeys mirrors store`
  - `volumeNavEvents forwards from volumeNavigationController`

- [ ] **Step 4.2: Lift code from VM L358–L376 + L2110–L2115.** No assisted scope needed — pure passthrough.

- [ ] **Step 4.3: Wire into VM.** Standard `@Provides` in `ReaderModule.kt` since no `CoroutineScope` is captured.

- [ ] **Step 4.4: Run app unit tests; commit.**
```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -20
git add app/src/main/kotlin/com/riffle/app/feature/reader/controllers/VolumeKeyDispatcher.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/controllers/VolumeKeyDispatcherTest.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/di/ReaderModule.kt
git commit -m "refactor(reader): extract VolumeKeyDispatcher (#303)"
```

---

## Task 5: Extract WakeLockController

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/controllers/WakeLockController.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/controllers/WakeLockControllerTest.kt`
- Modify: VM L293–L298 (`keepScreenOn`), L2105–L2107 (`setKeepScreenOn`).

**Interface produced:**
```kotlin
internal class WakeLockController(
    private val scope: OrchestratorScope,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val autoScrollState: StateFlow<AutoScrollState>,
) {
    val keepScreenOn: StateFlow<Boolean>
    suspend fun setKeepScreenOn(value: Boolean)
}
```

- [ ] **Step 5.1: Write tests (4):**
  - `keepScreenOn true when prefs allow and autoScroll is not running`
  - `keepScreenOn true when autoScroll is running regardless of prefs`
  - `keepScreenOn false when prefs deny and autoScroll not running`
  - `setKeepScreenOn delegates to store`

- [ ] **Step 5.2: Lift code from VM L293–L298 + L2105–L2107.**

- [ ] **Step 5.3: Wire — depends on `FormattingSession.autoScrollState`.** The VM constructs `WakeLockController` after `FormattingSession` in its init block and passes `formatting.autoScrollState` in.

- [ ] **Step 5.4: Run app unit tests; commit.**
```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/controllers/WakeLockController.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/controllers/WakeLockControllerTest.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "refactor(reader): extract WakeLockController (#303)"
```

---

## Task 6: Extract PositionOrchestrator

This is the hot-path concern. **High care required.**

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/session/PositionOrchestrator.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/session/PositionOrchestratorTest.kt`
- Modify: VM L1215–L1289 (`onPositionChanged`), L1361–L1429 (locator translation), L2050–L2065 (TOC nav), L225 (`_serverLocatorChannel`), L206 (`_pageTopProbeChannel`), L238 (`lastLocator`), L268 (`pendingServerJumpStamp`), L283 (`pendingReturnLocator`), L289 (`returnRestoreAttempts`), L262 (`initialLocatorSeen`), L236 (`suppressNextServerLocator`), L1717–L1748 (`_currentLocatorHref`, `_currentLocatorProgression`, `_currentLocatorTotalProgression`).

**Interface produced:**
```kotlin
internal class PositionOrchestrator @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val readingPositionStore: ReadingPositionStore,
    private val readingSessionRepository: ReadingSessionRepository,
    private val canonicalTranslator: CanonicalPositionTranslator,
) {
    val currentLocator: StateFlow<Locator?>
    val currentLocatorHref: StateFlow<String?>
    val currentLocatorProgression: StateFlow<Float?>
    val currentLocatorTotalProgression: StateFlow<Float?>
    val serverLocatorChannel: SharedFlow<Locator>
    val pageTopProbeChannel: SharedFlow<PageTopProbe>
    val pendingReturnLocator: StateFlow<Locator?>

    fun bindBook(bookId: Long, serverId: String, itemId: String, publication: Publication)
    fun onPositionChanged(locator: Locator)
    suspend fun navigateToHref(href: String, fragment: String?)
    suspend fun navigateToProgression(href: String, progression: Float)
    fun requestServerJump(locator: Locator)
    fun snapshotLastLocator(): Locator?
    fun setReturnAnchor(locator: Locator?)
    fun consumeReturnAnchor(): Locator?

    @AssistedFactory interface Factory { fun create(scope: CoroutineScope): PositionOrchestrator }
}
```

- [ ] **Step 6.1: Write tests (10):**
  - `onPositionChanged updates currentLocator, href, progression, totalProgression in one tick`
  - `onPositionChanged debounces persistence to readingPositionStore (e.g. 1 s)`
  - `requestServerJump emits to serverLocatorChannel and sets pendingServerJumpStamp`
  - `suppressNextServerLocator skips the next channel emission then re-arms`
  - `navigateToHref emits a navigation target that bumps the channel`
  - `setReturnAnchor + consumeReturnAnchor round-trip; consume clears the value`
  - `pendingReturnLocator retry counter caps at returnRestoreAttempts`
  - `bindBook re-initialises all state for a new book`
  - `initialLocatorSeen gates first-locator-replay logic`
  - `snapshotLastLocator returns the last reported locator after onPositionChanged`

- [ ] **Step 6.2: Lift code into PositionOrchestrator.** Maintain the same field names; only the receiver class changes. Replace `viewModelScope.launch` with `scope.launch`. **DO NOT** alter the `onPositionChanged` semantics — the auto-memory `reference_continuous_annotation_focus_reflow_race.md` shows reflow-race fixes live here.

- [ ] **Step 6.3: Make tests pass.**

- [ ] **Step 6.4: Wire into VM.** VM now exposes delegations:
```kotlin
val currentLocator = position.currentLocator
val currentLocatorHref = position.currentLocatorHref
val currentLocatorProgression = position.currentLocatorProgression
val currentLocatorTotalProgression = position.currentLocatorTotalProgression
val serverLocatorChannel = position.serverLocatorChannel
val pageTopProbeChannel = position.pageTopProbeChannel
val pendingReturnLocator = position.pendingReturnLocator
fun onPositionChanged(locator: Locator) = position.onPositionChanged(locator)
```

- [ ] **Step 6.5: Critical regression check — manually trace the VM's existing call sites for the lifted fields.** Use ripgrep to find every reference to `_currentLocatorHref`, `lastLocator`, `_serverLocatorChannel`, `pendingServerJumpStamp` and confirm each now goes through the orchestrator:
```bash
rg "lastLocator|_serverLocatorChannel|pendingServerJumpStamp|_currentLocatorHref|_pageTopProbeChannel" app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
```
Expected: zero results in the VM body (only constructor wiring + delegations remain).

- [ ] **Step 6.6: Run app unit tests.**
```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```

- [ ] **Step 6.7: Commit.**
```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/session/PositionOrchestrator.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/session/PositionOrchestratorTest.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "refactor(reader): extract PositionOrchestrator (#303)"
```

---

## Task 7: Extract AnnotationSession

Depends on PositionOrchestrator's `currentLocatorHref` + `currentLocatorProgression` for page bookmark detection, but those are now public on the orchestrator so wiring is straightforward.

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/session/AnnotationSession.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/session/AnnotationSessionTest.kt`
- Modify: VM L443, L450, L248, L454, L460, L1432–L1471 (observation setup), L1482–L1525 (creation), L1577–L1592 (mutation), L1598–L1602 (sync scheduling), L1614–L1637 (panel + nav), L1639–L1682 (render reconstruction), L1760–L1768 (panel/nav channels), L806–L829 (live sync init).

**Interface produced:**
```kotlin
internal class AnnotationSession @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val annotationStore: AnnotationStore,
    private val annotationSyncController: AnnotationSyncController,
    private val annotationStatusStore: AnnotationStatusStore,
) {
    val annotationsAvailable: StateFlow<Boolean>
    val highlightRenders: StateFlow<List<HighlightRender>>
    val highlightToEdit: StateFlow<Highlight?>
    val annotationsPanelVisible: StateFlow<Boolean>
    val annotations: StateFlow<List<Annotation>>
    val annotationNavigationChannel: SharedFlow<Locator>
    val syncBanner: StateFlow<AnnotationSyncBanner?>

    fun bind(serverId: String, namespace: String, itemId: String,
             currentLocator: StateFlow<Locator?>,
             publication: Publication)
    suspend fun createHighlight(req: SelectionEvent.HighlightRequest, color: HighlightColor)
    suspend fun recolorHighlight(id: String, color: HighlightColor)
    suspend fun deleteHighlight(id: String)
    suspend fun updateHighlightNote(id: String, note: String)
    fun openAnnotationsPanel()
    fun closeAnnotationsPanel()
    suspend fun navigateToAnnotation(id: String)
    suspend fun deleteAnnotation(id: String)
    fun onBookClosed()
    @AssistedFactory interface Factory { fun create(scope: CoroutineScope): AnnotationSession }
}
```

- [ ] **Step 7.1: Write tests (10):**
  - `createHighlight detects overlap and merges or rejects`
  - `createHighlight stores snippet derived from selection text+before+after`
  - `highlightRenders reactively reflects annotationStore.observeHighlights`
  - `recolorHighlight updates store and schedules debounce sync`
  - `deleteHighlight removes from store and schedules debounce sync`
  - `openAnnotationsPanel + navigateToAnnotation emits via channel`
  - `syncBanner reflects annotationStatusStore states (Syncing/Synced/Failed)`
  - `bind triggers syncOnOpen and startLiveSync`
  - `onBookClosed triggers syncOnClose and cancels live-sync job`
  - `live-sync job is single-flight per book` (memory: regression history)

- [ ] **Step 7.2: Lift code into AnnotationSession.**

- [ ] **Step 7.3: Make tests pass.**

- [ ] **Step 7.4: Wire into VM** with delegations. Pass `position.currentLocator` to `bind()`.

- [ ] **Step 7.5: Run app unit tests; commit.**
```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/session/AnnotationSession.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/session/AnnotationSessionTest.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "refactor(reader): extract AnnotationSession (#303)"
```

---

## Task 8: Extract ReadaloudSession

**The most coupled extraction.** The auto-memory points at this code path for the bulk of historical reader regressions. Take it slowly.

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/session/ReadaloudSession.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/session/ReadaloudSessionTest.kt`
- Modify: VM L483–L557 (state flows), L491–L505 (audio identity), L2117–L2723 (lifecycle, control, navigation, play state, track/quotes), L1070–L1163 (audiobook flush + mirror), L1193–L1213 (resume persist), L2285–L2312 (handoff), L2405–L2417 (streaming session), L2487–L2525 (private vars), L834–L856 (sentence-quote build + audiobook-follow loop).

**Interface produced:**
```kotlin
internal class ReadaloudSession @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val playerCoordinator: PlayerCoordinator,
    private val readaloudAudioRepository: ReadaloudAudioRepository,
    private val streamingSessionFactory: StreamingSessionFactory,
    private val readaloudResumeStore: ReadaloudResumeStore,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    private val readaloudPreferencesStore: ReadaloudPreferencesStore,
    private val sidecarStore: SidecarStore,
    private val nowPlayingStore: NowPlayingStore,
    private val progressFlushScope: ProgressFlushScope,
    private val audioSyncStore: AudioSyncStore,
    private val readingPositionStore: ReadingPositionStore,
) {
    val readaloudOpen: StateFlow<Boolean>
    val readaloudAvailable: StateFlow<Boolean>
    val readaloudVisible: StateFlow<Boolean>
    val downloadPromptBytes: StateFlow<Long?>
    val downloadProgress: StateFlow<DownloadProgress?>
    val readaloudBarMessage: StateFlow<String?>
    val sentenceQuotes: StateFlow<List<SentenceQuote>>
    val sentenceChapters: StateFlow<List<SentenceChapter>>
    val readaloudTrackFlow: StateFlow<ReadaloudTrack?>
    val audiobookItemId: StateFlow<String?>

    fun bind(serverId: String, itemId: String,
             effectiveFormatting: StateFlow<FormattingPreferences>,
             currentLocator: StateFlow<Locator?>,
             readerSyncProvider: () -> ReaderSync?)
    fun openReadaloud()
    fun closeReadaloud()
    suspend fun togglePlayPause()
    suspend fun setSpeed(speed: Float)
    suspend fun rewind()
    suspend fun forward()
    suspend fun previousChapter()
    suspend fun nextChapter()
    suspend fun onPlayTapped()
    suspend fun startReadaloudAtSecond(seconds: Double)
    suspend fun playFromHere(req: SelectionEvent.PlayFromHereRequest)
    suspend fun confirmDownload()
    fun prepareAudiobookHandoff()
    fun onAudiobookOverlayDismissed()
    fun onBookClosed()

    @AssistedFactory interface Factory { fun create(scope: CoroutineScope): ReadaloudSession }
}
```

- [ ] **Step 8.1: Write tests (14):**
  - `openReadaloud + onPlayTapped builds streaming session then prepares player`
  - `togglePlayPause toggles MediaController play/pause`
  - `setSpeed debounces persistence at 500ms`
  - `rewind seeks by rewindIntervalSec via player`
  - `forward seeks by skipIntervalSec via player`
  - `previousChapter / nextChapter dispatch audio-domain seek` (memory: `reference_readaloud_chapter_nav_uses_reader_toc.md`)
  - `startReadaloudAtSecond seeks player and ensures opened`
  - `playFromHere resolves sentence id, snaps to sentence, plays`
  - `confirmDownload starts download and updates downloadProgress`
  - `prepareAudiobookHandoff + onAudiobookOverlayDismissed maintain audiobookHandoffState`
  - `sentence-quote build emits when isPlaying transitions to true`
  - `audiobook flush PATCHes ABS via progressFlushScope (survives onCleared)` (regression: `reference_progress_flush_scope_teardown.md`)
  - `pause flushes readaloud position to readingPositionStore`
  - `bind re-initialises all state for a new book and cancels previous jobs`

- [ ] **Step 8.2: Lift code from the listed ranges.** Same `scope.launch` + `progressFlushScope.launch` discipline; do NOT collapse the two — that distinction is the reason progress survives teardown.

- [ ] **Step 8.3: Make tests pass.**

- [ ] **Step 8.4: Wire into VM** with delegations. Pass `formatting.effectiveFormattingPreferences` and `position.currentLocator` to `bind()`.

- [ ] **Step 8.5: Run app unit tests.**
```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```

- [ ] **Step 8.6: Commit.**
```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/session/ReadaloudSession.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/session/ReadaloudSessionTest.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "refactor(reader): extract ReadaloudSession (#303)"
```

---

## Task 9: Historical-regression tests (acceptance criterion)

Issue #303 acceptance: "pick 3 [historical reader bugs] and write regression tests at the orchestrator level. They must fail against pre-fix code and pass after." Since all three bugs are *already* fixed on `main`, the requirement is: write tests that, with the fix removed, fail; with the fix restored, pass.

For each: implement a test, then temporarily revert the fix locally, confirm the test fails, then re-apply the fix and confirm it passes. **Do not commit the revert.**

**Files:**
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/session/regressions/ContinuousAnnotationFocusReflowRaceTest.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/session/regressions/ReadaloudHighlightRotationReflowTest.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/session/regressions/ProgressFlushScopeTeardownTest.kt`

### 9a — Continuous annotation-focus reflow race
*(memory: `reference_continuous_annotation_focus_reflow_race.md`)*

- [ ] **Step 9a.1:** Test simulates a `PositionOrchestrator` bound in CONTINUOUS mode with annotation-focus restore. A fake presenter reports a `PageLoadGeneration` with an initial body-height of 200px, then a remeasure to 800px. Assertion: the orchestrator re-issues a navigation to the annotation's anchor on each remeasure until the remeasure is stable, and disarms on a synthetic touch event.

- [ ] **Step 9a.2:** Locally remove the "re-land on every target remeasure" logic, run the test, confirm RED.

- [ ] **Step 9a.3:** Restore the logic; confirm GREEN.

- [ ] **Step 9a.4:** Commit test only.

### 9b — Readaloud highlight wrong after rotation
*(memory: `reference_readaloud_highlight_rotation_reflow.md`)*

- [ ] **Step 9b.1:** Test asserts `ReadaloudSession` re-applies the active sentence decoration when `presenter.pageLoadEvents` emits a new `PageLoadGeneration` (a rotation re-creates the Activity and bumps the generation).

- [ ] **Step 9b.2:** Locally drop the pageLoadGeneration re-key collector; confirm RED.

- [ ] **Step 9b.3:** Restore; confirm GREEN.

- [ ] **Step 9b.4:** Commit test only.

### 9c — ProgressFlushScope teardown
*(memory: `reference_progress_flush_scope_teardown.md`)*

- [ ] **Step 9c.1:** Test asserts that `ReadaloudSession.onBookClosed()` schedules the final audiobook progress PATCH on `progressFlushScope`, not on the orchestrator's own `scope`. Use a TestDispatcher to cancel the orchestrator scope mid-PATCH and verify the PATCH still completes via the surviving `progressFlushScope`.

- [ ] **Step 9c.2:** Locally change the PATCH `launch` site to `scope.launch`; confirm RED.

- [ ] **Step 9c.3:** Restore to `progressFlushScope.launch`; confirm GREEN.

- [ ] **Step 9c.4:** Commit test only.

- [ ] **Step 9d: Final regression-test commit.**
```bash
git add app/src/test/kotlin/com/riffle/app/feature/reader/session/regressions/
git commit -m "test(reader): orchestrator-level regression tests for 3 historical bugs (#303)"
```

---

## Task 10: VM final audit + acceptance verification

- [ ] **Step 10.1: Audit the VM body.** Open `EpubReaderViewModel.kt`. Confirm:
  - `wc -l` reports ≤ 500 LOC.
  - Constructor param count ≤ 10. Print with:
    ```bash
    awk '/^class EpubReaderViewModel/,/^\) :/' app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
    ```
  - **No business logic in the VM body** — every method body is one of: `flow.collect { … }`, `scope.launch { … }` containing a single delegation, or a single forwarding call. Anything else gets moved to an orchestrator or deleted.
  - **Deletion-test invariant:** comment out the `formatting` field; the compiler must surface ≥1 unused constructor parameter (proving FormattingSession earns its keep). Repeat mentally for each orchestrator.

- [ ] **Step 10.2: Full unit-test suite.**
```bash
./gradlew test 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10.3: Harness tests.** Per AGENTS.md, never raw connectedAndroidTest:
```bash
make harness-test 2>&1 | tail -30
make harness-test-tablet 2>&1 | tail -30
```
Both must be green. Expect a self-managed AVD per memory `reference_harness_run_avd_gotchas.md`.

- [ ] **Step 10.4: AVD verification of all three reader modes** (per AGENTS.md "Reader mode changes" + memory `feedback_verify_in_avd_before_done.md`).
  - **You may build and install** only because the user has asked to do "all the work" (which includes acceptance verification). Confirm with the user once before `assembleDebug` + `adb install` if there's any doubt.
  - Smoke matrix:
    | Mode | Smoke action |
    |---|---|
    | Paginated | Open a book, page forward 5 pages, page back, highlight a sentence, add bookmark, search "the", play readaloud for 30s, change font size, rotate. |
    | Vertical | Same matrix, with scroll-driven position. |
    | Continuous | Same matrix, with native-scroll position; specifically reproduce the annotation-focus restore on a long chapter. |
  - Capture `adb logcat -d | grep -E 'RIFFLE_PROG|RIFFLE_RA'` after each mode; confirm no new error patterns vs. main.

- [ ] **Step 10.5: Commit any cleanup discovered during audit.**

- [ ] **Step 10.6: Open PR.** Per memory `feedback_pr_title_convention.md`:
```bash
gh pr create --base main \
  --title "refactor(reader): split EpubReaderViewModel into orchestrators (#303)" \
  --body "$(cat <<'EOF'
## Summary
Resolves #303. Splits the 2,785-LOC `EpubReaderViewModel` into a thin assembler (≤500 LOC, ≤10 deps) that holds 8 single-purpose orchestrators/controllers behind the existing `ReaderPresenter` seam (#300) and consuming `ProgressReconciler` (#302).

- `session/PositionOrchestrator` — canonical position stream
- `session/ReadaloudSession` — audio + highlight pipeline
- `session/AnnotationSession` — decorations + sync banner
- `session/FormattingSession` — typography + auto-scroll + auto theme
- `controllers/BookmarksController` + `controllers/SearchController` — leaf concerns
- `controllers/VolumeKeyDispatcher` + `controllers/WakeLockController` — passthrough leaves

Each orchestrator has a dedicated unit-test class against the existing `FakeReaderPresenter`; three historical reader regressions get orchestrator-level tests (continuous reflow race, readaloud rotation reflow, ProgressFlushScope teardown).

## Test plan
- [ ] `./gradlew test` green
- [ ] `make harness-test` green
- [ ] `make harness-test-tablet` green
- [ ] AVD smoke: paginated mode (paging, highlight, bookmark, search, readaloud, font, rotate)
- [ ] AVD smoke: vertical mode (same matrix)
- [ ] AVD smoke: continuous mode (same matrix + annotation-focus restore on long chapter)
EOF
)"
```

Do **not** push without explicit user consent (memory: `feedback_no_push_without_permission.md`).

---

## Self-review checklist (run after writing the plan)

1. **Spec coverage** — every issue #303 acceptance criterion has a task:
   - ≤10 constructor params, ≤500 LOC → Task 10 Step 1.
   - No business rule in VM body → Task 10 Step 1.
   - Each orchestrator has its own unit test class → Tasks 2, 3, 6, 7, 8 (FakeReaderPresenter exists per #300).
   - All existing reader behaviours preserved (3 modes) → Task 10 Step 4.
   - Harness tests green → Task 10 Step 3.
   - Deletion-test invariant → Task 10 Step 1.
   - 3 historical-regression tests → Task 9.

2. **Placeholder scan** — no TBD/TODO/"fill in" in the plan body.

3. **Type consistency** — orchestrator interfaces use the same names across tasks (`bind`, `onBookClosed`, `scope`, `Factory.create(scope)`). Public fields exposed by the VM use the same names as the orchestrator fields (e.g., `effectiveFormattingPreferences`, `currentLocator`).

4. **Hilt pattern consistency** — every orchestrator that captures `viewModelScope` uses `@AssistedInject` + `@AssistedFactory`. Pure passthroughs (`VolumeKeyDispatcher`) use plain `@Inject`.

5. **Memory respected** — `book_formatting_preferences` per-device (Task 2), `ScrollBoundaryNavigationContainer` not touched (Task 6 lifts consumers only), `progressFlushScope` survives (Task 8, Task 9c), no push without permission (Task 10 Step 6).
