# Not Started Filter Chip — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistently-visible "Not Started" filter chip to the All Books Tab that narrows the cover grid to items the user has never opened.

**Architecture:** Add a `MutableStateFlow<Boolean>` to `LibraryItemsViewModel` and expand the existing `filteredAllBooks` combine to include it as a third predicate (offline × notStarted). The UI chip lives in the `AllBooksTabContent` composable as a `FilterChip` inside a `LazyRow` header item — always rendered, never gated on count.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 (`FilterChip`), Coroutines `StateFlow`/`combine`.

## Global Constraints

- Filter predicate: `readingProgress == 0f` — exact equality, matching the existing In-Progress threshold (`> 0f`).
- Chip label: `"Not Started"` — applies to ebooks, audiobooks, and combined items uniformly.
- Chip is always visible regardless of filtered count (even when zero).
- Filter state is session-scoped (ViewModel `StateFlow`, no DataStore persistence).
- Filter state is per-library (each library has its own ViewModel instance).
- Offline filter and Not Started filter compose with AND logic.
- Run tests with: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest`

---

### Task 1: ViewModel — notStartedFilterActive state + updated filteredAllBooks

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsViewModel.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemsViewModelTest.kt`

**Interfaces:**
- Produces: `val notStartedFilterActive: StateFlow<Boolean>` and `fun toggleNotStartedFilter()`
- The existing `val filteredAllBooks: StateFlow<List<LibraryItem>>` gains a third predicate.

- [ ] **Step 1: Write the failing tests**

Add to `LibraryItemsViewModelTest.kt` (after the existing `filteredAllBooks` tests, around line 373):

```kotlin
// --- notStartedFilterActive ---

@Test
fun `notStartedFilterActive starts as false`() = runTest {
    val vm = makeViewModel()
    backgroundScope.launch { vm.notStartedFilterActive.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.notStartedFilterActive.value)
}

@Test
fun `toggleNotStartedFilter flips active to true`() = runTest {
    val vm = makeViewModel()
    backgroundScope.launch { vm.notStartedFilterActive.collect {} }
    vm.toggleNotStartedFilter()
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.notStartedFilterActive.value)
}

@Test
fun `toggleNotStartedFilter flips active back to false on second call`() = runTest {
    val vm = makeViewModel()
    backgroundScope.launch { vm.notStartedFilterActive.collect {} }
    vm.toggleNotStartedFilter()
    vm.toggleNotStartedFilter()
    testDispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.notStartedFilterActive.value)
}

@Test
fun `filteredAllBooks shows only zero-progress items when notStartedFilterActive`() = runTest {
    val vm = makeViewModel()
    backgroundScope.launch { vm.filteredAllBooks.collect {} }
    backgroundScope.launch { vm.notStartedFilterActive.collect {} }
    allBooksFlow.value = listOf(
        LibraryItem("id-Dune", "lib-1", "Dune", "Herbert", null, 0f, false, false, EbookFormat.Epub),
        LibraryItem("id-Martian", "lib-1", "The Martian", "Weir", null, 0.42f, false, false, EbookFormat.Epub),
        LibraryItem("id-Wool", "lib-1", "Wool", "Howey", null, 1f, false, false, EbookFormat.Epub),
    )
    vm.toggleNotStartedFilter()
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(
        listOf(LibraryItem("id-Dune", "lib-1", "Dune", "Herbert", null, 0f, false, false, EbookFormat.Epub)),
        vm.filteredAllBooks.value,
    )
}

@Test
fun `filteredAllBooks shows all items when notStartedFilter is toggled back off`() = runTest {
    val vm = makeViewModel()
    backgroundScope.launch { vm.filteredAllBooks.collect {} }
    val all = listOf(
        LibraryItem("id-Dune", "lib-1", "Dune", "Herbert", null, 0f, false, false, EbookFormat.Epub),
        LibraryItem("id-Martian", "lib-1", "The Martian", "Weir", null, 0.42f, false, false, EbookFormat.Epub),
    )
    allBooksFlow.value = all
    vm.toggleNotStartedFilter()
    vm.toggleNotStartedFilter()
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(all, vm.filteredAllBooks.value)
}

@Test
fun `filteredAllBooks not-started filter composes with offline filter`() = runTest {
    val vm = makeViewModel(
        connectivityObserver = FakeConnectivityObserver(online = false),
        epubRepository = fakeEpubRepoWithDownloads(setOf("id-Dune")),
    )
    backgroundScope.launch { vm.filteredAllBooks.collect {} }
    backgroundScope.launch { vm.isOffline.collect {} }
    allBooksFlow.value = listOf(
        // not started AND downloaded — should appear
        LibraryItem("id-Dune", "lib-1", "Dune", "Herbert", null, 0f, false, false, EbookFormat.Epub),
        // not started but NOT downloaded — offline filter removes it
        LibraryItem("id-Foundation", "lib-1", "Foundation", "Asimov", null, 0f, false, false, EbookFormat.Epub),
        // in progress AND downloaded — not-started filter removes it
        LibraryItem("id-Martian", "lib-1", "The Martian", "Weir", null, 0.42f, false, false, EbookFormat.Epub),
    )
    vm.toggleNotStartedFilter()
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(
        listOf(LibraryItem("id-Dune", "lib-1", "Dune", "Herbert", null, 0f, false, false, EbookFormat.Epub)),
        vm.filteredAllBooks.value,
    )
}

@Test
fun `filteredAllBooks includes zero-progress audiobook-only items when notStartedFilterActive`() = runTest {
    val vm = makeViewModel()
    backgroundScope.launch { vm.filteredAllBooks.collect {} }
    val audiobook = LibraryItem(
        "id-Audiobook", "lib-1", "Project Hail Mary", "Weir", null, 0f, false, false,
        EbookFormat.Unsupported, hasAudio = true,
    )
    allBooksFlow.value = listOf(
        audiobook,
        LibraryItem("id-Started", "lib-1", "Dune", "Herbert", null, 0.1f, false, false, EbookFormat.Epub),
    )
    vm.toggleNotStartedFilter()
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(audiobook), vm.filteredAllBooks.value)
}
```

- [ ] **Step 2: Run tests — confirm they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.library.LibraryItemsViewModelTest" 2>&1 | grep -E "FAILED|PASSED|ERROR|notStarted|Unresolved"
```

Expected: tests fail with `Unresolved reference: notStartedFilterActive` and `Unresolved reference: toggleNotStartedFilter`.

- [ ] **Step 3: Add state and toggle to ViewModel**

In `LibraryItemsViewModel.kt`, replace lines 205–207:

```kotlin
val filteredAllBooks: StateFlow<List<LibraryItem>> = combine(allBooks, isOffline) { items, offline ->
    if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

with:

```kotlin
private val _notStartedFilterActive = MutableStateFlow(false)
val notStartedFilterActive: StateFlow<Boolean> = _notStartedFilterActive.asStateFlow()

fun toggleNotStartedFilter() {
    _notStartedFilterActive.value = !_notStartedFilterActive.value
}

val filteredAllBooks: StateFlow<List<LibraryItem>> = combine(
    allBooks,
    isOffline,
    _notStartedFilterActive,
) { items, offline, notStartedOnly ->
    val afterOffline = if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
    if (notStartedOnly) afterOffline.filter { it.readingProgress == 0f } else afterOffline
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

- [ ] **Step 4: Run tests — confirm they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.library.LibraryItemsViewModelTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsViewModel.kt \
        app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemsViewModelTest.kt
git commit -m "feat(library): add Not Started filter state to LibraryItemsViewModel"
```

---

### Task 2: UI — Not Started chip in AllBooksTabContent

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt`

**Interfaces:**
- Consumes: `notStartedFilterActive: StateFlow<Boolean>` and `toggleNotStartedFilter()` from Task 1.
- No new public interface — changes are internal to the screen composable.

- [ ] **Step 1: Add missing imports**

In `LibraryItemsScreen.kt`, add the following imports alongside the existing `import` block (after the existing `material3` and `foundation.lazy` imports):

```kotlin
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
```

- [ ] **Step 2: Collect notStartedFilterActive in the screen**

In `LibraryItemsScreen.kt`, in the screen composable's state-collection block (around line 128–143, after the `val linkedItemIds` line), add:

```kotlin
val notStartedFilterActive by viewModel.notStartedFilterActive.collectAsState()
```

- [ ] **Step 3: Pass new params to AllBooksTabContent at the call site**

Find the `4 -> AllBooksTabContent(` block (around line 269) and add the two new named arguments:

```kotlin
4 -> AllBooksTabContent(
    items = allBooks,
    isLoading = isLoading,
    token = viewModel.authToken,
    onItemSelected = onItemSelected,
    linkedItemIds = linkedItemIds,
    onCoverScaleChange = onCoverScaleChange,
    notStartedFilterActive = notStartedFilterActive,
    onToggleNotStartedFilter = viewModel::toggleNotStartedFilter,
)
```

- [ ] **Step 4: Replace AllBooksTabContent with the chip-row version**

Replace the entire `AllBooksTabContent` function (lines 1195–1229) with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllBooksTabContent(
    items: List<LibraryItem>,
    isLoading: Boolean,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    linkedItemIds: Set<String> = emptySet(),
    onCoverScaleChange: (Float) -> Unit = {},
    notStartedFilterActive: Boolean = false,
    onToggleNotStartedFilter: () -> Unit = {},
) {
    if (isLoading) return
    LazyVerticalGrid(
        columns = GridCells.Adaptive(coverGridMinCellSize()),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        modifier = Modifier
            .pinchCoverZoom(onCoverScaleChange)
            .fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionHeader("All Books (${items.size})")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = notStartedFilterActive,
                            onClick = onToggleNotStartedFilter,
                            label = { Text("Not Started") },
                            leadingIcon = if (notStartedFilterActive) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }
        if (items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (notStartedFilterActive) "No unstarted books" else "No items in this library",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(items, key = { it.id }) { item ->
            Box(modifier = Modifier.padding(4.dp)) {
                BookCoverTile(
                    item = item,
                    token = token,
                    onClick = { onItemSelected(item) },
                    hasReadaloudLink = item.id in linkedItemIds,
                )
            }
        }
    }
}
```

- [ ] **Step 5: Verify the project compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run full unit test suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt
git commit -m "feat(library): add Not Started filter chip to All Books Tab"
```
