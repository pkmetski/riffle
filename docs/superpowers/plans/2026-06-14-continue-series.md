# Continue Series Home Section — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Continue Series" horizontal section between "In Progress" and "Recently Added" on the library home tab, showing the next unread book in each series where the user has finished at least one book.

**Architecture:** A new SQL query in `SeriesDao` returns one `LibraryItemEntity` per qualifying series (min `sequenceOrder` where `readingProgress < 1.0`, only for series that have ≥1 finished book), ordered by most-recently-finished sibling. The result flows through `LibraryRepository` → `LibraryItemsViewModel` → `HomeTabContent` following the exact same pattern as the existing "In Progress" section. A new `seriesNameBadge: String?` parameter on `BookCoverTile` renders a dark pill overlay at the top-centre of the cover.

**Tech Stack:** Kotlin, Jetpack Compose, Room (SQLite), Kotlin Flows/StateFlow, Hilt

---

### Task 1: SeriesDao — observeContinueSeriesItems query

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/SeriesDao.kt`
- Test: `core/database/src/androidTest/kotlin/com/riffle/core/database/SeriesDaoTest.kt`

- [ ] **Step 1: Write the failing test (androidTest)**

Add to `SeriesDaoTest.kt` inside the class, after `observeItemsBySeriesId_returnsItemsInSequenceOrder`:

```kotlin
// C1 — returns the next unread book per series (min sequenceOrder, readingProgress < 1.0)
//      only for series that have ≥1 finished book; ordered by most-recently-finished sibling DESC
@Test
fun observeContinueSeriesItems_returnsNextUnreadBookPerQualifyingSeries() = runTest {
    // Three items: item-1 finished, item-2 unread, item-3 unread (different series)
    db.libraryItemDao().upsertAll(listOf(
        LibraryItemEntity(serverId = "s1", id = "item-1", libraryId = "lib1", title = "Book 1", author = "A", coverUrl = null, readingProgress = 1.0f, lastOpenedAt = 1000L),
        LibraryItemEntity(serverId = "s1", id = "item-2", libraryId = "lib1", title = "Book 2", author = "A", coverUrl = null, readingProgress = 0f),
        LibraryItemEntity(serverId = "s1", id = "item-3", libraryId = "lib1", title = "Book 3", author = "A", coverUrl = null, readingProgress = 0f),
    ))
    dao.upsertAll(listOf(series("series-A"), series("series-B")))
    dao.upsertAllItems(listOf(
        // series-A: item-1 finished (seq 1), item-2 unread (seq 2) → should return item-2
        SeriesItemEntity("series-A", serverId = "s1", itemId = "item-1", sequenceOrder = 1f),
        SeriesItemEntity("series-A", serverId = "s1", itemId = "item-2", sequenceOrder = 2f),
        // series-B: item-3 only unread, no finished book → must NOT appear
        SeriesItemEntity("series-B", serverId = "s1", itemId = "item-3", sequenceOrder = 1f),
    ))

    val result = dao.observeContinueSeriesItems("lib1").first()

    assertEquals(listOf("item-2"), result.map { it.id })
}

// C2 — skips partially-read books; returns first fully-unread one
@Test
fun observeContinueSeriesItems_skipsPartiallyReadBook() = runTest {
    db.libraryItemDao().upsertAll(listOf(
        LibraryItemEntity(serverId = "s1", id = "item-1", libraryId = "lib1", title = "B1", author = "A", coverUrl = null, readingProgress = 1.0f, lastOpenedAt = 2000L),
        LibraryItemEntity(serverId = "s1", id = "item-2", libraryId = "lib1", title = "B2", author = "A", coverUrl = null, readingProgress = 0.5f),
        LibraryItemEntity(serverId = "s1", id = "item-3", libraryId = "lib1", title = "B3", author = "A", coverUrl = null, readingProgress = 0f),
    ))
    dao.upsertAll(listOf(series("series-A")))
    dao.upsertAllItems(listOf(
        SeriesItemEntity("series-A", serverId = "s1", itemId = "item-1", sequenceOrder = 1f),
        SeriesItemEntity("series-A", serverId = "s1", itemId = "item-2", sequenceOrder = 2f),
        SeriesItemEntity("series-A", serverId = "s1", itemId = "item-3", sequenceOrder = 3f),
    ))

    // item-2 has readingProgress = 0.5 which is < 1.0, so it IS the next book
    val result = dao.observeContinueSeriesItems("lib1").first()

    assertEquals(listOf("item-2"), result.map { it.id })
}

// C3 — multiple qualifying series ordered by most-recently-finished sibling DESC
@Test
fun observeContinueSeriesItems_orderedByMostRecentlyFinished() = runTest {
    db.libraryItemDao().upsertAll(listOf(
        // series-old: finished long ago
        LibraryItemEntity(serverId = "s1", id = "old-done", libraryId = "lib1", title = "OD", author = "A", coverUrl = null, readingProgress = 1.0f, lastOpenedAt = 1000L),
        LibraryItemEntity(serverId = "s1", id = "old-next", libraryId = "lib1", title = "ON", author = "A", coverUrl = null, readingProgress = 0f),
        // series-new: finished recently
        LibraryItemEntity(serverId = "s1", id = "new-done", libraryId = "lib1", title = "ND", author = "A", coverUrl = null, readingProgress = 1.0f, lastOpenedAt = 9000L),
        LibraryItemEntity(serverId = "s1", id = "new-next", libraryId = "lib1", title = "NN", author = "A", coverUrl = null, readingProgress = 0f),
    ))
    dao.upsertAll(listOf(series("series-old"), series("series-new")))
    dao.upsertAllItems(listOf(
        SeriesItemEntity("series-old", serverId = "s1", itemId = "old-done", sequenceOrder = 1f),
        SeriesItemEntity("series-old", serverId = "s1", itemId = "old-next", sequenceOrder = 2f),
        SeriesItemEntity("series-new", serverId = "s1", itemId = "new-done", sequenceOrder = 1f),
        SeriesItemEntity("series-new", serverId = "s1", itemId = "new-next", sequenceOrder = 2f),
    ))

    val result = dao.observeContinueSeriesItems("lib1").first()

    // series-new finished more recently → new-next must come first
    assertEquals(listOf("new-next", "old-next"), result.map { it.id })
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# These are instrumented tests — do NOT run now; they'll be verified in step 5 after the implementation.
# Instead, confirm the DAO interface is missing the method by checking compilation:
./gradlew :core:database:assembleAndroidTest 2>&1 | tail -20
```

Expected: compile error — `observeContinueSeriesItems` not found on `SeriesDao`.

- [ ] **Step 3: Add the query to SeriesDao**

In `SeriesDao.kt`, add after `observeItemsBySeriesId`:

```kotlin
@Query("""
    SELECT li.* FROM library_items li
    INNER JOIN series_items si ON li.serverId = si.serverId AND li.id = si.itemId
    WHERE li.libraryId = :libraryId
      AND li.readingProgress < 1.0
      AND si.seriesId IN (
          SELECT DISTINCT si2.seriesId
          FROM series_items si2
          INNER JOIN library_items li2 ON li2.serverId = si2.serverId AND li2.id = si2.itemId
          WHERE li2.libraryId = :libraryId AND li2.readingProgress = 1.0
      )
      AND si.sequenceOrder = (
          SELECT MIN(si3.sequenceOrder)
          FROM series_items si3
          INNER JOIN library_items li3 ON li3.serverId = si3.serverId AND li3.id = si3.itemId
          WHERE si3.seriesId = si.seriesId
            AND li3.libraryId = :libraryId
            AND li3.readingProgress < 1.0
      )
    ORDER BY COALESCE(
        (
            SELECT MAX(li4.lastOpenedAt)
            FROM series_items si4
            INNER JOIN library_items li4 ON li4.serverId = si4.serverId AND li4.id = si4.itemId
            WHERE si4.seriesId = si.seriesId
              AND li4.libraryId = :libraryId
              AND li4.readingProgress = 1.0
        ), 0
    ) DESC
""")
fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItemEntity>>
```

- [ ] **Step 4: Compile androidTest module**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:database:assembleAndroidTest 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run the three new DAO tests on the Harness AVD**

```bash
make harness-test 2>&1 | grep -E "observeContinueSeriesItems|PASS|FAIL|ERROR"
```

Expected: all three `observeContinueSeriesItems_*` tests PASS.

- [ ] **Step 6: Commit**

```bash
git add core/database/src/main/kotlin/com/riffle/core/database/SeriesDao.kt \
        core/database/src/androidTest/kotlin/com/riffle/core/database/SeriesDaoTest.kt
git commit -m "feat(database): add observeContinueSeriesItems DAO query"
```

---

### Task 2: LibraryRepository interface + FakeSeriesDao stub

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/LibraryRepository.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/LibraryRepositoryTest.kt` (FakeSeriesDao)

- [ ] **Step 1: Add the method to the LibraryRepository interface**

In `LibraryRepository.kt`, add after `observeSeriesItems`:

```kotlin
fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>>
```

- [ ] **Step 2: Add the stub to FakeSeriesDao in LibraryRepositoryTest**

In `LibraryRepositoryTest.kt`, inside `FakeSeriesDao`, add after `findSeriesIdForItem`:

```kotlin
private val continueSeriesData = mutableMapOf<String, MutableStateFlow<List<LibraryItemEntity>>>()

override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItemEntity>> =
    continueSeriesData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

fun seedContinueSeriesItems(libraryId: String, items: List<LibraryItemEntity>) {
    continueSeriesData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }.value = items
}
```

- [ ] **Step 3: Confirm the project compiles (RepositoryImpl will fail — that's expected)**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:compileDebugUnitTestKotlin 2>&1 | tail -20
```

Expected: compile error about `LibraryRepositoryImpl` not implementing `observeContinueSeriesItems`. That's fine — fixed in Task 3.

- [ ] **Step 4: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/LibraryRepository.kt \
        core/data/src/test/kotlin/com/riffle/core/data/LibraryRepositoryTest.kt
git commit -m "feat(domain): add observeContinueSeriesItems to LibraryRepository interface"
```

---

### Task 3: LibraryRepositoryImpl — implement observeContinueSeriesItems

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/LibraryRepositoryImpl.kt`

- [ ] **Step 1: Write a failing repository unit test**

In `LibraryRepositoryTest.kt`, add a new test after the existing series tests:

```kotlin
@Test
fun `observeContinueSeriesItems maps entities from DAO`() = runTest {
    val dao = FakeSeriesDao()
    val repo = makeRepo(seriesDao = dao)
    dao.seedContinueSeriesItems("lib-1", listOf(
        LibraryItemEntity(
            serverId = "s1", id = "item-42", libraryId = "lib-1",
            title = "Abaddon's Gate", author = "James S. A. Corey",
            coverUrl = null, readingProgress = 0f, seriesName = "The Expanse",
        ),
    ))

    val result = repo.observeContinueSeriesItems("lib-1").first()

    assertEquals(1, result.size)
    assertEquals("item-42", result[0].id)
    assertEquals("Abaddon's Gate", result[0].title)
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test 2>&1 | grep -E "observeContinueSeriesItems|PASS|FAIL|ERROR|error:"
```

Expected: compile error — `observeContinueSeriesItems` not implemented in `LibraryRepositoryImpl`.

- [ ] **Step 3: Implement the method in LibraryRepositoryImpl**

In `LibraryRepositoryImpl.kt`, add after `observeSeriesItems`:

```kotlin
override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> =
    seriesDao.observeContinueSeriesItems(libraryId).map { list -> list.map { it.toDomain() } }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test 2>&1 | grep -E "observeContinueSeriesItems|PASS|FAIL|BUILD"
```

Expected: BUILD SUCCESSFUL, test passes.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/LibraryRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/LibraryRepositoryTest.kt
git commit -m "feat(data): implement observeContinueSeriesItems in LibraryRepositoryImpl"
```

---

### Task 4: LibraryItemsViewModel — continueSeriesItems StateFlow + test

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsViewModel.kt`
- Modify: `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemsViewModelTest.kt`

- [ ] **Step 1: Write the failing ViewModel test**

In `LibraryItemsViewModelTest.kt`:

Add a new flow field after `recentlyAddedFlow`:
```kotlin
private val continueSeriesFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
```

Add the override to `fakeRepo()` inside the `object : LibraryRepository { ... }` block, after `observeRecentlyAddedItems`:
```kotlin
override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = continueSeriesFlow
```

Add the test after the existing home tab section tests:
```kotlin
@Test
fun `continueSeriesItems reflects repository emissions`() = runTest {
    val vm = makeViewModel()
    backgroundScope.launch { vm.continueSeriesItems.collect {} }

    val nextBook = item("Abaddon's Gate", "James S. A. Corey")
    continueSeriesFlow.value = listOf(nextBook)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(nextBook), vm.continueSeriesItems.value)
}

@Test
fun `continueSeriesItems filters to offline-available items when offline`() = runTest {
    val availableItem = item("Offline Book", "Author A")
    val unavailableItem = item("Online Only", "Author B")
    val connectivity = FakeConnectivityObserver(online = false)
    val epubRepo = object : EpubRepository by fakeEpubRepo() {
        override fun isCached(serverId: String, itemId: String): Boolean = itemId == availableItem.id
    }
    val vm = makeViewModel(connectivityObserver = connectivity, epubRepository = epubRepo)
    backgroundScope.launch { vm.continueSeriesItems.collect {} }

    continueSeriesFlow.value = listOf(availableItem, unavailableItem)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(availableItem), vm.continueSeriesItems.value)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.library.LibraryItemsViewModelTest.continueSeriesItems*" 2>&1 | tail -20
```

Expected: compile error — `continueSeriesItems` not found on `LibraryItemsViewModel`, and `observeContinueSeriesItems` missing from `fakeRepo`.

- [ ] **Step 3: Add continueSeriesItems to LibraryItemsViewModel**

In `LibraryItemsViewModel.kt`, add a private base flow after the `recentlyAdded` declaration (line ~117):

```kotlin
private val continueSeriesBase: StateFlow<List<LibraryItem>> = libraryRepository.observeContinueSeriesItems(libraryId)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Then add the public filtered flow after `filteredRecentlyAdded` (around line ~196):

```kotlin
val continueSeriesItems: StateFlow<List<LibraryItem>> = combine(continueSeriesBase, isOffline) { items, offline ->
    if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.library.LibraryItemsViewModelTest.continueSeriesItems*" 2>&1 | tail -20
```

Expected: both tests PASS.

- [ ] **Step 5: Run full test suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsViewModel.kt \
        app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemsViewModelTest.kt
git commit -m "feat(library): add continueSeriesItems StateFlow to LibraryItemsViewModel"
```

---

### Task 5: UI — series badge on BookCoverTile + HomeTabContent section

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibrarySectionType.kt`

- [ ] **Step 1: Add CONTINUE_SERIES to LibrarySectionType**

In `LibrarySectionType.kt`, replace the file content with:

```kotlin
package com.riffle.app.feature.library

enum class LibrarySectionType {
    IN_PROGRESS, FINISHED, RECENTLY_ADDED, CONTINUE_SERIES;

    val displayName: String get() = when (this) {
        IN_PROGRESS    -> "In Progress"
        FINISHED       -> "Completed"
        RECENTLY_ADDED -> "Recently Added"
        CONTINUE_SERIES -> "Continue Series"
    }
}
```

- [ ] **Step 2: Add seriesNameBadge parameter to BookCoverTile**

In `LibraryItemsScreen.kt`, find `fun BookCoverTile(` (line 462). Change the signature and add the badge overlay inside the `Box`:

New signature:
```kotlin
fun BookCoverTile(
    item: LibraryItem,
    token: String,
    onClick: () -> Unit,
    hasReadaloudLink: Boolean = false,
    seriesNameBadge: String? = null,
)
```

Inside the `Box` (which already contains the cover image, progress indicator, and readaloud badge), add the series badge **after** the `if (hasReadaloudLink)` block and before the closing `}` of the Box:

```kotlin
if (seriesNameBadge != null) {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.70f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = seriesNameBadge,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

Make sure `androidx.compose.ui.unit.sp` is imported (add `import androidx.compose.ui.unit.sp` if not already present).

- [ ] **Step 3: Add showSeriesBadge parameter to BookSectionGrid**

Find `fun BookSectionGrid(` and update its signature:

```kotlin
@Composable
fun BookSectionGrid(
    items: List<LibraryItem>,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    onSeeMore: (() -> Unit)? = null,
    linkedItemIds: Set<String> = emptySet(),
    showSeriesBadge: Boolean = false,
)
```

Inside the `CoverGridLayout` lambda, update the `BookCoverTile` call to pass the badge:

```kotlin
BookCoverTile(
    item = item,
    token = token,
    onClick = { onItemSelected(item) },
    hasReadaloudLink = item.id in linkedItemIds,
    seriesNameBadge = if (showSeriesBadge) item.seriesName else null,
)
```

- [ ] **Step 4: Update HomeTabContent signature and body**

Find `private fun HomeTabContent(` (line 978). Add `continueSeries` parameter and update the empty-state guard:

New signature (add `continueSeries` after `inProgress`):
```kotlin
@Composable
private fun HomeTabContent(
    inProgress: List<LibraryItem>,
    continueSeries: List<LibraryItem>,
    recentlyAdded: List<LibraryItem>,
    finished: List<LibraryItem>,
    isLoading: Boolean,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    linkedItemIds: Set<String> = emptySet(),
    onCoverScaleChange: (Float) -> Unit = {},
)
```

Update the empty-state guard at the top of the function body:
```kotlin
if (inProgress.isEmpty() && continueSeries.isEmpty() && recentlyAdded.isEmpty() && finished.isEmpty()) {
```

In the `LazyColumn` body, insert the Continue Series section between the "In Progress" block and the "Recently Added" block:

```kotlin
if (continueSeries.isNotEmpty()) {
    item(key = "header_continue_series") { SectionHeader(LibrarySectionType.CONTINUE_SERIES.displayName) }
    item(key = "grid_continue_series") {
        BookSectionGrid(
            items = continueSeries,
            token = token,
            onItemSelected = onItemSelected,
            onSeeMore = null,
            linkedItemIds = linkedItemIds,
            showSeriesBadge = true,
        )
    }
}
```

- [ ] **Step 5: Update the HomeTabContent call site**

Find the call site at line ~233:
```kotlin
0 -> HomeTabContent(
    inProgress = inProgress,
    recentlyAdded = recentlyAdded,
    finished = finished,
    ...
)
```

Add the missing state collection near the other `collectAsState()` calls (around line 130):
```kotlin
val continueSeries by viewModel.continueSeriesItems.collectAsState()
```

Then update the call:
```kotlin
0 -> HomeTabContent(
    inProgress = inProgress,
    continueSeries = continueSeries,
    recentlyAdded = recentlyAdded,
    finished = finished,
    isLoading = isLoading,
    token = viewModel.authToken,
    onItemSelected = onItemSelected,
    onSectionSeeMore = onSectionSeeMore,
    linkedItemIds = linkedItemIds,
    onCoverScaleChange = onCoverScaleChange,
)
```

- [ ] **Step 6: Compile the app**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Run full JVM test suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibrarySectionType.kt \
        app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt
git commit -m "feat(library): add Continue Series section to home tab"
```
