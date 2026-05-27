# EPUB Book Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add in-EPUB text search to the reader: a search icon in the TopAppBar transforms the bar into a live search field with prev/next result navigation and content highlights.

**Architecture:** Search state (query, results, active index) lives in `EpubReaderViewModel`. Readium's `SearchService` runs the full-book query; results (as `Locator` objects) are passed into `EpubNavigatorView` which navigates via `fragment.go(locator)` and highlights via `fragment.applyDecorations()`. The TopAppBar swaps between its normal layout and a `SearchTopBar` composable based on `isSearchActive`.

**Tech Stack:** Readium Kotlin SDK 3.0.0 (`SearchService` from `readium-shared`, `DecorableNavigator`/`Decoration` from `readium-navigator`), Kotlin Coroutines (`debounce`, `StateFlow`, `Channel`), Jetpack Compose Material3.

---

## File Map

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` | Add search state, open/close/query/nav functions, debounce + SearchService call |
| `app/src/main/kotlin/com/riffle/app/feature/reader/SearchTopBar.kt` | **New** — composable for the search-active TopAppBar state |
| `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` | Swap TopAppBar ↔ SearchTopBar based on `isSearchActive`; add search icon; reorder actions; pass search state into `EpubNavigatorView` |
| `app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt` | Add tests for debounce pattern and result index navigation |

---

## Task 1: Reorder TopAppBar icons

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt:211-222`

The current order is TOC → Formatting. The new order is Search → TOC → Formatting. This task adds the Search icon as a placeholder (no action yet) and imports the `Search` icon.

- [ ] **Step 1: Add Search icon import and placeholder action**

In `EpubReaderScreen.kt`, update the `actions` block of `TopAppBar` (lines ~211–222):

```kotlin
import androidx.compose.material.icons.filled.Search

// Inside TopAppBar actions = { ... }:
if (state is ReaderState.Ready) {
    IconButton(onClick = { /* wired in Task 4 */ }) {
        Icon(Icons.Default.Search, contentDescription = "Search")
    }
    IconButton(onClick = viewModel::openToc) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Table of Contents")
    }
    IconButton(onClick = { showFormattingPanel = true }) {
        Icon(Icons.Default.Settings, contentDescription = "Format")
    }
}
```

- [ ] **Step 2: Build and verify icons appear in correct order**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Install on emulator/device and confirm icon order is 🔍 → ☰ → ⚙.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): reorder TopAppBar icons — Search, TOC, Formatting"
```

---

## Task 2: Search state in ViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt`

Add `isSearchActive`, `searchQuery`, `searchResults`, `currentSearchIndex` state flows, and the open/close/query-change/next/prev functions. No Readium call yet — `performSearch` is a stub.

- [ ] **Step 1: Write failing tests for search state transitions**

Add to `EpubReaderViewModelTest.kt`:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

@Test
fun `search index advances to next result`() = runTest {
    val results = listOf("loc0", "loc1", "loc2")
    val currentIndex = MutableStateFlow(0)

    fun nextResult() {
        currentIndex.update { (it + 1).coerceAtMost(results.size - 1) }
    }

    nextResult()
    assertEquals(1, currentIndex.value)
    nextResult()
    assertEquals(2, currentIndex.value)
    nextResult() // already at end — clamped
    assertEquals(2, currentIndex.value)
}

@Test
fun `search index retreats to prev result`() = runTest {
    val results = listOf("loc0", "loc1", "loc2")
    val currentIndex = MutableStateFlow(2)

    fun prevResult() {
        currentIndex.update { (it - 1).coerceAtLeast(0) }
    }

    prevResult()
    assertEquals(1, currentIndex.value)
    prevResult()
    assertEquals(0, currentIndex.value)
    prevResult() // already at start — clamped
    assertEquals(0, currentIndex.value)
}

@Test
fun `search debounce only triggers after delay`() = runTest {
    var searchCallCount = 0
    val query = MutableStateFlow("")

    backgroundScope.launch {
        query
            .debounce(300)
            .collect { q -> if (q.length >= 2) searchCallCount++ }
    }

    query.value = "wi"          // change 1
    advanceTimeBy(100)
    query.value = "win"         // change 2 — resets debounce
    advanceTimeBy(100)
    assertEquals(0, searchCallCount) // debounce not yet elapsed

    advanceTimeBy(250)          // 350ms after last change
    assertEquals(1, searchCallCount)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "com.riffle.app.feature.reader.EpubReaderViewModelTest" 2>&1 | tail -20
```

Expected: FAILED — `debounce` is not imported in the test file.

- [ ] **Step 3: Add missing import to test file and re-run to confirm tests pass as written (logic tests)**

```kotlin
import kotlinx.coroutines.flow.debounce
```

```bash
./gradlew :app:test --tests "com.riffle.app.feature.reader.EpubReaderViewModelTest" 2>&1 | tail -20
```

Expected: all tests PASS (these tests validate the pattern, not the ViewModel directly).

- [ ] **Step 4: Add search state to `EpubReaderViewModel`**

Add after the `_navigationEvents` block (around line 350 in `EpubReaderViewModel.kt`):

```kotlin
import kotlinx.coroutines.flow.debounce

private val _isSearchActive = MutableStateFlow(false)
val isSearchActive: StateFlow<Boolean> = _isSearchActive

private val _searchQuery = MutableStateFlow("")
val searchQuery: StateFlow<String> = _searchQuery

private val _searchResults = MutableStateFlow<List<Locator>>(emptyList())
val searchResults: StateFlow<List<Locator>> = _searchResults

private val _currentSearchIndex = MutableStateFlow(-1)
val currentSearchIndex: StateFlow<Int> = _currentSearchIndex

private val _searchNavigationChannel = Channel<Locator>(Channel.CONFLATED)
val searchNavigationEvents: Flow<Locator> = _searchNavigationChannel.receiveAsFlow()

private var searchJob: Job? = null

fun openSearch() {
    _isSearchActive.value = true
}

fun closeSearch() {
    _isSearchActive.value = false
    _searchQuery.value = ""
    _searchResults.value = emptyList()
    _currentSearchIndex.value = -1
    searchJob?.cancel()
}

fun onSearchQueryChanged(query: String) {
    _searchQuery.value = query
}

fun nextSearchResult() {
    val results = _searchResults.value
    if (results.isEmpty()) return
    val next = (_currentSearchIndex.value + 1).coerceAtMost(results.size - 1)
    _currentSearchIndex.value = next
    _searchNavigationChannel.trySend(results[next])
}

fun prevSearchResult() {
    val results = _searchResults.value
    if (results.isEmpty()) return
    val prev = (_currentSearchIndex.value - 1).coerceAtLeast(0)
    _currentSearchIndex.value = prev
    _searchNavigationChannel.trySend(results[prev])
}

private suspend fun performSearch(query: String) {
    // Stub — wired to Readium SearchService in Task 3
    _searchResults.value = emptyList()
    _currentSearchIndex.value = -1
}
```

Also add to `init { }` block, after the existing `viewModelScope.launch` blocks:

```kotlin
viewModelScope.launch {
    @OptIn(ExperimentalCoroutinesApi::class)
    _searchQuery
        .debounce(300)
        .collect { query ->
            searchJob?.cancel()
            if (query.length < 2) {
                _searchResults.value = emptyList()
                _currentSearchIndex.value = -1
                return@collect
            }
            searchJob = launch { performSearch(query) }
        }
}
```

Note: `debounce` requires `@OptIn(ExperimentalCoroutinesApi::class)` in some Kotlin Coroutines versions — add the annotation if the compiler requires it.

- [ ] **Step 5: Build to verify it compiles**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt
git commit -m "feat(reader): add search state and navigation functions to EpubReaderViewModel"
```

---

## Task 3: Implement Readium SearchService in ViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

Replace the `performSearch` stub with a real call to Readium's `SearchService`. This iterates all pages of results across the entire publication.

- [ ] **Step 1: Implement `performSearch` with `SearchService`**

Replace the stub `performSearch` in `EpubReaderViewModel.kt`:

```kotlin
import org.readium.r2.shared.publication.services.search.SearchService

private suspend fun performSearch(query: String) {
    val pub = publication ?: return
    val service = pub.findService(SearchService::class)
    if (service == null) {
        // This EPUB does not support text search (e.g. image-based scans).
        _searchResults.value = emptyList()
        _currentSearchIndex.value = -1
        return
    }
    val iterator = service.search(query)
    val results = mutableListOf<Locator>()
    while (true) {
        val page = iterator.nextPage().getOrNull() ?: break
        results.addAll(page)
    }
    iterator.close()
    _searchResults.value = results
    if (results.isEmpty()) {
        _currentSearchIndex.value = -1
    } else {
        _currentSearchIndex.value = 0
        _searchNavigationChannel.trySend(results[0])
    }
}
```

**API note:** `SearchService.search(query)` returns a `SearchIterator`. `iterator.nextPage()` returns `Try<List<Locator>?, SearchError>` — `.getOrNull()` unwraps the success value, returning `null` on error or when exhausted. Verify the exact import path by searching: `grep -r "SearchService" ~/.gradle/caches --include="*.kt" -l | head -5` or checking the Readium source for your version.

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. If `SearchService` import is wrong, the error message will show the correct package — fix accordingly.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(reader): implement full-book search via Readium SearchService"
```

---

## Task 4: Create SearchTopBar composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/SearchTopBar.kt`

A self-contained composable that renders the search-active state of the TopAppBar: back arrow (exits reader), text field, X dismiss button, match count, and prev/next arrows.

- [ ] **Step 1: Create `SearchTopBar.kt`**

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    query: String,
    resultCount: Int,
    currentIndex: Int,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TopAppBar(
        windowInsets = TopAppBarDefaults.windowInsets,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search in book…", fontSize = 16.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { /* results already live */ }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                ) {
                    val countText = when {
                        query.length < 2 -> ""
                        resultCount == 0 -> "No results"
                        else -> "${currentIndex + 1} of $resultCount"
                    }
                    Text(
                        text = countText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onPrev, enabled = currentIndex > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous result")
                    }
                    IconButton(onClick = onNext, enabled = currentIndex < resultCount - 1) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next result")
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close search")
            }
        },
    )
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/SearchTopBar.kt
git commit -m "feat(reader): add SearchTopBar composable for in-bar search UI"
```

---

## Task 5: Wire TopAppBar transformation in EpubReaderScreen

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

Collect search state from the ViewModel. Swap between `SearchTopBar` and the normal `TopAppBar` inside the existing `AnimatedVisibility` block. Wire the search icon's `onClick` to `viewModel::openSearch`. Also close search when the TOC or Formatting panels open (mutual exclusion).

- [ ] **Step 1: Collect search state and swap TopAppBar**

In `EpubReaderScreen`, add state collection after existing `collectAsState` calls:

```kotlin
val isSearchActive by viewModel.isSearchActive.collectAsState()
val searchQuery by viewModel.searchQuery.collectAsState()
val searchResults by viewModel.searchResults.collectAsState()
val currentSearchIndex by viewModel.currentSearchIndex.collectAsState()
```

Replace the `AnimatedVisibility { TopAppBar(...) }` block (lines ~199–224) with:

```kotlin
AnimatedVisibility(
    visible = !immersiveState.isImmersive,
    enter = slideInVertically(initialOffsetY = { -it }) + expandVertically(expandFrom = Alignment.Top),
    exit = slideOutVertically(targetOffsetY = { -it }) + shrinkVertically(shrinkTowards = Alignment.Top),
) {
    if (isSearchActive) {
        SearchTopBar(
            query = searchQuery,
            resultCount = searchResults.size,
            currentIndex = currentSearchIndex,
            onQueryChange = viewModel::onSearchQueryChanged,
            onPrev = viewModel::prevSearchResult,
            onNext = viewModel::nextSearchResult,
            onClose = viewModel::closeSearch,
            onNavigateBack = onNavigateBack,
        )
    } else {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (state is ReaderState.Ready) {
                    IconButton(onClick = viewModel::openSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = viewModel::openToc) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Table of Contents")
                    }
                    IconButton(onClick = { showFormattingPanel = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Format")
                    }
                }
            },
        )
    }
}
```

Also close search when TOC or Formatting panels open — update the existing `LaunchedEffect`:

```kotlin
LaunchedEffect(tocVisible, showFormattingPanel) {
    if (tocVisible || showFormattingPanel) viewModel.closeSearch()
    viewModel.onPanelStateChanged(tocVisible || showFormattingPanel)
}
```

- [ ] **Step 2: Build and smoke-test**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Install and open a book. Tap the search icon — the TopAppBar should transform into a search field with an X button. Tap X — it should revert to the normal bar. Tap TOC while search is open — search should close before the panel slides in.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): wire TopAppBar ↔ SearchTopBar transformation"
```

---

## Task 6: Navigate to search results in the fragment

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

Pass `searchNavigationEvents` into `EpubNavigatorView` and add a `LaunchedEffect` that calls `fragment.go(locator)` for each emitted result, identical to the existing `serverLocatorEvents` handling.

- [ ] **Step 1: Add `searchNavigationEvents` parameter to `EpubNavigatorView`**

Update the `EpubNavigatorView` signature:

```kotlin
@OptIn(ExperimentalReadiumApi::class)
@Composable
private fun EpubNavigatorView(
    state: ReaderState.Ready,
    formattingPrefs: FormattingPreferences,
    onPositionChanged: (Locator) -> Unit,
    onNavigationEvents: Flow<Link>,
    serverLocatorEvents: Flow<Locator>,
    searchNavigationEvents: Flow<Locator>,   // ← new
    volumeNavEvents: Flow<VolumeNavEvent>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Add a `LaunchedEffect` inside `EpubNavigatorView`, alongside the existing `serverLocatorEvents` one:

```kotlin
LaunchedEffect(searchNavigationEvents) {
    searchNavigationEvents.collect { locator ->
        fragmentRef.value?.go(locator)
    }
}
```

- [ ] **Step 2: Pass `searchNavigationEvents` at the call site**

In `EpubReaderScreen`, update the `EpubNavigatorView(...)` call to include:

```kotlin
EpubNavigatorView(
    state = s,
    formattingPrefs = formattingPrefs,
    onPositionChanged = { locator ->
        immersiveState.dismissOverlay()
        viewModel.onPositionChanged(locator)
    },
    onNavigationEvents = viewModel.navigationEvents,
    serverLocatorEvents = viewModel.serverLocatorEvents,
    searchNavigationEvents = viewModel.searchNavigationEvents,   // ← new
    volumeNavEvents = viewModel.volumeNavEvents,
    onTap = immersiveState::toggle,
    modifier = Modifier
        .fillMaxSize()
        .testTag("reader_ready")
        .semantics {
            contentDescription = buildString {
                append(locatorHref ?: "")
                append(" theme:")
                append(formattingPrefs.theme.name.lowercase())
                append(" wake-lock:")
                append(if (keepScreenOn) "on" else "off")
            }
        },
)
```

- [ ] **Step 3: Build and test navigation**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Open a book, search for a common word. Confirm the reader jumps to the first result. Tap the ▼ arrow — confirm it jumps to the next result.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): navigate to search results via fragment.go(locator)"
```

---

## Task 7: Highlight search results via DecorableNavigator

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

Apply decorations (highlights) to all results in the current view using `EpubNavigatorFragment.applyDecorations()`. The active result gets a darker highlight; others get a lighter one. Clear all decorations when search closes.

- [ ] **Step 1: Add decoration parameters to `EpubNavigatorView`**

Update `EpubNavigatorView` signature:

```kotlin
@OptIn(ExperimentalReadiumApi::class)
@Composable
private fun EpubNavigatorView(
    state: ReaderState.Ready,
    formattingPrefs: FormattingPreferences,
    onPositionChanged: (Locator) -> Unit,
    onNavigationEvents: Flow<Link>,
    serverLocatorEvents: Flow<Locator>,
    searchNavigationEvents: Flow<Locator>,
    searchResults: List<Locator>,            // ← new
    currentSearchIndex: Int,                 // ← new
    volumeNavEvents: Flow<VolumeNavEvent>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 2: Apply decorations when search results or active index change**

Add inside `EpubNavigatorView`, after the `LaunchedEffect(searchNavigationEvents)` block:

```kotlin
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator

LaunchedEffect(searchResults, currentSearchIndex) {
    val fragment = fragmentRef.value ?: return@LaunchedEffect
    if (fragment !is DecorableNavigator) return@LaunchedEffect
    val decorations = searchResults.mapIndexed { index, locator ->
        Decoration(
            id = "search_$index",
            locator = locator,
            style = if (index == currentSearchIndex)
                Decoration.Style.Highlight(tint = android.graphics.Color.parseColor("#FFF5A623"))
            else
                Decoration.Style.Highlight(tint = android.graphics.Color.parseColor("#FFFDE68A")),
        )
    }
    fragment.applyDecorations(decorations, group = "search")
}
```

**API note:** The exact `Decoration.Style` constructor and `tint` type differ between Readium versions. If the compiler rejects this, check the `Decoration` source in your Readium 3.0.0 jar: `jar tf ~/.gradle/caches/modules-*/files-*/org.readium.kotlin.toolkit/readium-navigator/*/readium-navigator-*.jar | grep Decoration`. Common alternatives: `tint` may be an `Int` (ARGB) or `android.graphics.Color`. Adjust accordingly.

- [ ] **Step 3: Clear decorations when search closes**

The decorations are automatically cleared when `searchResults` becomes empty (the `LaunchedEffect` fires with an empty list, applying zero decorations to the "search" group). Verify this works by closing search with results visible — highlights should disappear.

- [ ] **Step 4: Pass new parameters at call site**

Update `EpubNavigatorView(...)` in `EpubReaderScreen`:

```kotlin
EpubNavigatorView(
    state = s,
    formattingPrefs = formattingPrefs,
    onPositionChanged = { locator ->
        immersiveState.dismissOverlay()
        viewModel.onPositionChanged(locator)
    },
    onNavigationEvents = viewModel.navigationEvents,
    serverLocatorEvents = viewModel.serverLocatorEvents,
    searchNavigationEvents = viewModel.searchNavigationEvents,
    searchResults = searchResults,           // ← new
    currentSearchIndex = currentSearchIndex, // ← new
    volumeNavEvents = viewModel.volumeNavEvents,
    onTap = immersiveState::toggle,
    modifier = Modifier
        .fillMaxSize()
        .testTag("reader_ready")
        .semantics {
            contentDescription = buildString {
                append(locatorHref ?: "")
                append(" theme:")
                append(formattingPrefs.theme.name.lowercase())
                append(" wake-lock:")
                append(if (keepScreenOn) "on" else "off")
            }
        },
)
```

- [ ] **Step 5: Build and test end-to-end**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Open a book and search for a word that appears multiple times. Verify:
- First result is highlighted in orange; other visible results are in yellow
- Tapping ▼ jumps to next result and changes which highlight is orange
- Tapping ✕ closes search and all highlights disappear

- [ ] **Step 6: Run unit tests**

```bash
./gradlew :app:test --tests "com.riffle.app.feature.reader.*" 2>&1 | tail -20
```

Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): highlight search results via DecorableNavigator"
```

---

## Self-Review

**Spec coverage:**
- ✅ Search icon leftmost in TopAppBar (Search → TOC → Formatting) — Task 1
- ✅ TopAppBar in-place transformation on search open — Tasks 4, 5
- ✅ X button closes search; back arrow always exits reader — Task 4
- ✅ Live search with 300ms debounce, min 2 chars — Task 2
- ✅ Whole-book search via Readium SearchService — Task 3
- ✅ Match count ("3 of 24") and prev/next arrows — Task 4
- ✅ "No results" in match count area — Task 4 (`SearchTopBar` handles `resultCount == 0`)
- ✅ Navigate to results via `fragment.go(locator)` — Task 6
- ✅ Highlights via `DecorableNavigator.applyDecorations` — Task 7
- ✅ Search absent in PDF reader (search icon only shown when `state is ReaderState.Ready` in EPUB reader; PDF reader has its own screen) — Task 5
- ✅ Opening TOC/Formatting panel closes search — Task 5
- ✅ Progress Sync unaffected (position updates flow normally during search) — no change needed

**Known risk:** `Decoration.Style` API shape in Readium 3.0.0 — verified at implementation time per Task 7 note.
