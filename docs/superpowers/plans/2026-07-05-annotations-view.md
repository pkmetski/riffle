# Annotations View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an "Annotations" surface — a Navigation Drawer entry that lists every book on the active ABS Server with highlights, and, on tap, opens an **elided reader** showing only those highlights (with attached notes), reusing the full EPUB reader's affordances.

**Architecture:** New drawer entry + list screen backed by the local `AnnotationEntity` Room store. Opening a book navigates to `EpubReaderScreen` in a new `ReaderSource.Highlights` mode; the reader ViewModel loads a **synthesised in-memory Readium `Publication`** (built by a new `HighlightsPublicationFactory`) instead of the ABS EPUB. `Progress Sync`, `Reading Session`, highlight-creation gestures, `Readaloud`, and the `Chapter Navigation Rail` are gated off in Highlights mode; everything else (formatting prefs, TOC, search, Cadence, Auto-Scroll, immersive, volume nav, wake lock, highlight action sheet) reuses the existing code paths untouched. Feature is Server-scoped and works entirely from local data (no source-EPUB dependency).

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, Kotlinx Coroutines/Flow, Readium Kotlin SDK (`EpubNavigatorFragment` + custom `ContinuousReaderView`), Coil.

## Global Constraints

- Follow ADR 0041 (`docs/adr/0041-annotations-view-elided-reader-over-local-store.md`) — three load-bearing decisions: (a) rendered from annotation data alone, (b) no Progress Sync + no Reading Session in Highlights mode, (c) new `ReaderSource` flag on the existing `EpubReaderScreen`, not a sibling screen.
- Follow the `[Annotations View]` glossary entry in `CONTEXT.md` for all user-visible copy and behaviour.
- Icon: outlined bookmark (`Icons.Outlined.BookmarkBorder`).
- Drawer entry name: **"Annotations"**. Position: between the Libraries block and Downloads.
- List sort: most-recently-annotated (max `updatedAt` across the book's highlights).
- Empty state copy: `"No highlights yet. Long-press text while reading to highlight it."`.
- "Book not available" message on Open-in-book failures (offline + uncached, or `library_items` row missing).
- Never use log literals — inject `Logger` and call `logger.d(LogChannel.X)` per AGENTS.md.
- Reference constants at every call site — never redeclare `AnnotationEntity.TYPE_HIGHLIGHT` locally.
- Every task ends with an automated regression test that would flip red if the change were reverted. No "manual verification only" tasks.
- JVM tests are the default; instrumentation only where the WebView / Readium is on the critical path.
- Commit at the end of each task.

---

## File Structure

**New files:**
- `core/data/src/main/kotlin/com/riffle/core/data/AnnotationsLibraryRepository.kt` — read-model for "books with highlights on this server", plus the elided-reader data feed.
- `core/data/src/main/kotlin/com/riffle/core/data/AnnotationsLibraryRepositoryImpl.kt` — implementation joining `annotations` ⋈ `library_items`.
- `core/data/src/test/kotlin/com/riffle/core/data/AnnotationsLibraryRepositoryTest.kt` — JVM test using `FakeAnnotationDao` + `FakeLibraryItemDao`.
- `app/src/main/kotlin/com/riffle/app/feature/annotations/AnnotationsListScreen.kt` — cover grid + empty state.
- `app/src/main/kotlin/com/riffle/app/feature/annotations/AnnotationsListViewModel.kt` — state flow of `List<AnnotatedBook>`.
- `app/src/test/kotlin/com/riffle/app/feature/annotations/AnnotationsListViewModelTest.kt` — VM test.
- `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/ReaderSource.kt` — the source mode enum.
- `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPublicationFactory.kt` — synthesises an in-memory Readium `Publication` from `List<AnnotationEntity>` + chapter-title lookup.
- `app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPublicationFactoryTest.kt` — JVM test asserting spine structure and chapter HTML.
- `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsResumeStore.kt` — DataStore-backed per-device resume position (`(serverId, itemId) → lastHighlightId`).
- `app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsResumeStoreTest.kt`.
- `docs/superpowers/plans/2026-07-05-annotations-view.md` — this file.

**Modified files:**
- `core/database/src/main/kotlin/com/riffle/core/database/AnnotationDao.kt` — new `observeBooksWithHighlights(serverId)` query.
- `app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerComposable.kt` — new "Annotations" entry.
- `app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerViewModel.kt` — new `onAnnotationsSelected` target.
- `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt` — new `ANNOTATIONS` route + composable; extend `EPUB_READER` route with `?source=highlights`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` — accept `source: ReaderSource`; branch publication load; gate Progress Sync + Reading Session + highlight-creation.
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` — hide Readaloud toggle + Chapter Navigation Rail when `source == Highlights`; add "Open in book" to `HighlightActionsSheet`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/HighlightActionsSheet.kt` — add "Open in book" action.
- `app/src/main/kotlin/com/riffle/app/ui/theme/RiffleIcons.kt` — add `Annotations` icon token (`BookmarkBorder`).

**Test-only:**
- `core/data/src/test/kotlin/com/riffle/core/data/FakeAnnotationDao.kt` (extend if it exists, else create).

---

## Task 1: `AnnotationDao.observeBooksWithHighlights`

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/AnnotationDao.kt`
- Test: `core/database/src/androidTest/kotlin/com/riffle/core/database/AnnotationDaoTest.kt` (extend if it exists; else create — check first).

**Interfaces:**
- Consumes: `AnnotationEntity` schema (v-current), constants `TYPE_HIGHLIGHT`, `deleted` flag.
- Produces:

```kotlin
data class BookHighlightSummary(
    val itemId: String,
    val highlightCount: Int,
    val latestUpdatedAt: Long,
)

@Query("""
    SELECT itemId,
           COUNT(*) AS highlightCount,
           MAX(updatedAt) AS latestUpdatedAt
    FROM annotations
    WHERE serverId = :serverId
      AND type = 'HIGHLIGHT'
      AND deleted = 0
    GROUP BY itemId
    ORDER BY latestUpdatedAt DESC
""")
fun observeBooksWithHighlights(serverId: String): Flow<List<BookHighlightSummary>>
```

- [ ] **Step 1: Write the failing instrumentation test**

Add to `core/database/src/androidTest/kotlin/com/riffle/core/database/AnnotationDaoTest.kt` (create if missing, mirroring `LibraryItemDaoTest.kt` structure):

```kotlin
@Test
fun observeBooksWithHighlights_groupsByItemAndSortsByLatestUpdatedAt() = runTest {
    val db = Room.inMemoryDatabaseBuilder(context, RiffleDatabase::class.java)
        .allowMainThreadQueries().build()
    val serverDao = db.serverDao()
    val dao = db.annotationDao()
    serverDao.insert(ServerEntity(id = "S1", url = "http://x", username = "u"))

    // Book A: 2 highlights (older), Book B: 1 highlight (newer), Book C: only bookmark (excluded),
    // Book D: highlight but soft-deleted (excluded).
    listOf(
        highlight("a1", "S1", "A", updatedAt = 100),
        highlight("a2", "S1", "A", updatedAt = 200),
        highlight("b1", "S1", "B", updatedAt = 300),
        bookmark ("c1", "S1", "C", updatedAt = 400),
        highlight("d1", "S1", "D", updatedAt = 500, deleted = true),
    ).forEach { dao.upsert(it) }

    val result = dao.observeBooksWithHighlights("S1").first()

    assertEquals(2, result.size)
    assertEquals("B", result[0].itemId)
    assertEquals(1, result[0].highlightCount)
    assertEquals(300L, result[0].latestUpdatedAt)
    assertEquals("A", result[1].itemId)
    assertEquals(2, result[1].highlightCount)
    assertEquals(200L, result[1].latestUpdatedAt)

    db.close()
}
```

Helpers (`highlight(...)`, `bookmark(...)`) construct `AnnotationEntity` with sensible defaults; reference `AnnotationEntity.TYPE_HIGHLIGHT` and `TYPE_BOOKMARK` constants.

- [ ] **Step 2: Run test to verify it fails**

```
ANDROID_SERIAL=<harness_serial> ./gradlew :core:database:connectedDebugAndroidTest \
    --tests com.riffle.core.database.AnnotationDaoTest.observeBooksWithHighlights_groupsByItemAndSortsByLatestUpdatedAt
```

Expected: FAIL — `observeBooksWithHighlights` unresolved reference.

- [ ] **Step 3: Add the query and DTO**

In `AnnotationDao.kt`, add the `BookHighlightSummary` DTO (top-level `data class` in the same file) and the `@Query` method exactly as in the Interfaces block above.

- [ ] **Step 4: Run test to verify PASS**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```
git add core/database
git commit -m "feat(annotations): add DAO query for books with highlights"
```

---

## Task 2: `AnnotationsLibraryRepository`

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationsLibraryRepository.kt`
- Create: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationsLibraryRepositoryImpl.kt`
- Create: `core/data/src/test/kotlin/com/riffle/core/data/AnnotationsLibraryRepositoryTest.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/modules/RepositoriesModule.kt` (bind the impl)

**Interfaces:**
- Consumes: `AnnotationDao.observeBooksWithHighlights` (Task 1), `LibraryItemDao.observeByServer(serverId)` (existing — verify exact name in first step).
- Produces:

```kotlin
data class AnnotatedBook(
    val serverId: String,
    val itemId: String,
    val title: String?,          // null when library_items row is gone (text-only card)
    val author: String?,
    val coverUrl: String?,
    val highlightCount: Int,
    val latestUpdatedAt: Long,
)

interface AnnotationsLibraryRepository {
    fun observeAnnotatedBooks(serverId: String): Flow<List<AnnotatedBook>>
}
```

- [ ] **Step 1: Verify `LibraryItemDao` observe-by-server signature**

```
grep -nE "observeByServer|Flow<List<LibraryItemEntity>>" core/database/src/main/kotlin/com/riffle/core/database/LibraryItemDao.kt
```

Note the exact method name and use it in Step 3. If the closest available flow is `observeForServer(serverId)`, use that.

- [ ] **Step 2: Write the failing JVM test**

`AnnotationsLibraryRepositoryTest.kt`:

```kotlin
class AnnotationsLibraryRepositoryTest {
    @Test
    fun mergesSummariesWithLibraryItemsAndSortsByLatestUpdatedAt() = runTest {
        val annDao = FakeAnnotationDao().apply {
            emitBooksWithHighlights(
                "S1",
                listOf(
                    BookHighlightSummary("A", 2, 200),
                    BookHighlightSummary("B", 1, 300),
                    BookHighlightSummary("Z", 4, 999), // no library_items row → text-only card
                ),
            )
        }
        val libDao = FakeLibraryItemDao().apply {
            emit(
                "S1",
                listOf(
                    libItem("A", title = "Alpha", author = "AA", coverUrl = "urlA"),
                    libItem("B", title = "Bravo", author = "BB", coverUrl = "urlB"),
                ),
            )
        }
        val repo = AnnotationsLibraryRepositoryImpl(annDao, libDao)

        val result = repo.observeAnnotatedBooks("S1").first()

        assertEquals(listOf("Z", "B", "A"), result.map { it.itemId })
        assertEquals(AnnotatedBook("S1", "Z", null, null, null, 4, 999), result[0])
        assertEquals("Alpha", result[2].title)
        assertEquals(2, result[2].highlightCount)
    }
}
```

If `FakeAnnotationDao` doesn't expose `emitBooksWithHighlights`, extend it (test-only file) with a `MutableStateFlow<List<BookHighlightSummary>>` per server.

- [ ] **Step 3: Run test to verify FAIL**

```
./gradlew :core:data:test --tests com.riffle.core.data.AnnotationsLibraryRepositoryTest
```

Expected: FAIL — implementation missing.

- [ ] **Step 4: Implement**

`AnnotationsLibraryRepository.kt` — interface + `AnnotatedBook` data class as in Interfaces block.

`AnnotationsLibraryRepositoryImpl.kt`:

```kotlin
class AnnotationsLibraryRepositoryImpl @Inject constructor(
    private val annotationDao: AnnotationDao,
    private val libraryItemDao: LibraryItemDao,
) : AnnotationsLibraryRepository {

    override fun observeAnnotatedBooks(serverId: String): Flow<List<AnnotatedBook>> =
        combine(
            annotationDao.observeBooksWithHighlights(serverId),
            libraryItemDao.observeForServer(serverId).map { rows ->
                rows.associateBy { it.id }
            },
        ) { summaries, itemsById ->
            summaries.map { s ->
                val item = itemsById[s.itemId]
                AnnotatedBook(
                    serverId = serverId,
                    itemId = s.itemId,
                    title = item?.title,
                    author = item?.author,
                    coverUrl = item?.coverUrl,
                    highlightCount = s.highlightCount,
                    latestUpdatedAt = s.latestUpdatedAt,
                )
            }.sortedByDescending { it.latestUpdatedAt }
        }
}
```

Bind in `RepositoriesModule.kt` with `@Binds`.

- [ ] **Step 5: Run test to verify PASS**

Same command. Expected: PASS.

- [ ] **Step 6: Commit**

```
git add core/data
git commit -m "feat(annotations): add AnnotationsLibraryRepository"
```

---

## Task 3: `AnnotationsListViewModel` + `AnnotationsListScreen`

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/annotations/AnnotationsListViewModel.kt`
- Create: `app/src/main/kotlin/com/riffle/app/feature/annotations/AnnotationsListScreen.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/annotations/AnnotationsListViewModelTest.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/ui/theme/RiffleIcons.kt`

**Interfaces:**
- Consumes: `AnnotationsLibraryRepository` (Task 2), active-server flow (see how `LibraryItemsViewModel` obtains it — pattern to mirror).
- Produces:

```kotlin
data class AnnotationsListUiState(
    val loading: Boolean = true,
    val books: List<AnnotatedBook> = emptyList(),
)

@HiltViewModel
class AnnotationsListViewModel @Inject constructor(
    private val repo: AnnotationsLibraryRepository,
    private val activeServer: ActiveServerHolder, // whichever type the drawer uses
) : ViewModel() {
    val state: StateFlow<AnnotationsListUiState> = /* … */
}
```

- [ ] **Step 1: Locate the active-server pattern**

```
grep -nE "activeServer|ActiveServer|current.*Server|currentServerId" app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsViewModel.kt app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerViewModel.kt | head
```

Use the same holder / flow. If it's a `Flow<ServerId?>`, `flatMapLatest` into the repo.

- [ ] **Step 2: Add the icon token**

In `RiffleIcons.kt`:

```kotlin
val Annotations: ImageVector = Icons.Outlined.BookmarkBorder
```

- [ ] **Step 3: Write the failing VM test**

`AnnotationsListViewModelTest.kt`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationsListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After  fun after()  = Dispatchers.resetMain()

    @Test
    fun emitsRepoBooksForActiveServer() = runTest(dispatcher) {
        val repo = FakeAnnotationsLibraryRepository().apply {
            emit("S1", listOf(annotatedBook("A", 2, 200), annotatedBook("B", 1, 300)))
        }
        val server = MutableStateFlow<String?>("S1")
        val vm = AnnotationsListViewModel(repo, FakeActiveServer(server))

        val state = vm.state.first { !it.loading }
        assertEquals(listOf("B", "A"), state.books.map { it.itemId })
    }

    @Test
    fun switchesServerAndReflectsNewList() = runTest(dispatcher) {
        val repo = FakeAnnotationsLibraryRepository().apply {
            emit("S1", listOf(annotatedBook("A", 1, 100)))
            emit("S2", listOf(annotatedBook("X", 3, 999)))
        }
        val server = MutableStateFlow<String?>("S1")
        val vm = AnnotationsListViewModel(repo, FakeActiveServer(server))

        vm.state.first { it.books.singleOrNull()?.itemId == "A" }
        server.value = "S2"
        vm.state.first { it.books.singleOrNull()?.itemId == "X" }
    }
}
```

- [ ] **Step 4: Run test to verify FAIL**

```
./gradlew :app:testDebugUnitTest --tests com.riffle.app.feature.annotations.AnnotationsListViewModelTest
```

Expected: FAIL — VM missing.

- [ ] **Step 5: Implement VM and Screen**

`AnnotationsListViewModel.kt`:

```kotlin
@HiltViewModel
class AnnotationsListViewModel @Inject constructor(
    private val repo: AnnotationsLibraryRepository,
    private val activeServer: ActiveServerHolder,
) : ViewModel() {
    val state: StateFlow<AnnotationsListUiState> =
        activeServer.serverIdFlow
            .flatMapLatest { id ->
                if (id == null) flowOf(AnnotationsListUiState(loading = false))
                else repo.observeAnnotatedBooks(id)
                    .map { AnnotationsListUiState(loading = false, books = it) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnnotationsListUiState())
}
```

`AnnotationsListScreen.kt` — cover grid mirroring `LibraryItemsScreen` (adaptive cell sizing via `CoverGridSizing`), each card showing cover + title + author + a highlight-count badge (small Surface top-right of the cover; text = `count.toString()`). Text-only fallback when `title == null`: render a placeholder cover box with the item id / "Unknown book" and the count badge below. Empty state Composable: centred column with the exact copy `"No highlights yet."` + one-line hint `"Long-press text while reading to highlight it."`.

Wire an `onBookClick: (serverId, itemId) -> Unit` up to the caller (nav-graph will pass it in Task 5).

- [ ] **Step 6: Run test to verify PASS**

Same command. Expected: PASS.

- [ ] **Step 7: Commit**

```
git add app
git commit -m "feat(annotations): add AnnotationsListScreen + VM"
```

---

## Task 4: Navigation Drawer entry

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerComposable.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerViewModel.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/navigation/NavigationDrawerViewModelTest.kt` (extend or create)

**Interfaces:**
- Produces: a new drawer target `NavigationTarget.Annotations` (or the equivalent existing sealed hierarchy — inspect first).

- [ ] **Step 1: Inspect the existing target model**

```
grep -nE "sealed.*Navigation|NavigationTarget|onDownloadsSelected|onSettingsSelected" app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawer*.kt app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt | head -20
```

- [ ] **Step 2: Write the failing VM test**

Extend `NavigationDrawerViewModelTest.kt` with a case that asserts the drawer VM emits an `Annotations` selection when its callback fires. If drawer selection is a plain `onAnnotationsSelected: () -> Unit` composable input (no VM state), skip the VM test and instead add a **Compose UI test** (Robolectric or `androidx.compose.ui.test.junit4`) asserting: (a) an item labelled `"Annotations"` appears between the visible-libraries block and `"Downloads"`, (b) tapping it invokes the passed-in lambda exactly once.

- [ ] **Step 3: Run test to verify FAIL**

```
./gradlew :app:testDebugUnitTest --tests com.riffle.app.feature.navigation.NavigationDrawerViewModelTest
```

Expected: FAIL.

- [ ] **Step 4: Add the drawer entry**

In `NavigationDrawerComposable.kt`, insert a new `NavigationDrawerItem` between the last visible-library row and the Downloads row:

```kotlin
NavigationDrawerItem(
    icon = { Icon(RiffleIcons.Annotations, contentDescription = null) },
    label = { Text("Annotations") },
    selected = false,
    onClick = onAnnotationsSelected,
    modifier = Modifier.padding(horizontal = 12.dp),
)
```

Add `onAnnotationsSelected: () -> Unit` to the composable's signature (alongside `onDownloadsSelected`). Thread it through the sheetBody / permanent variants.

- [ ] **Step 5: Run test to verify PASS**

Same command. Expected: PASS.

- [ ] **Step 6: Commit**

```
git add app
git commit -m "feat(annotations): add Annotations drawer entry"
```

---

## Task 5: Navigation route + `MainScreen` wiring

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/ReaderSource.kt` (Task 6 also modifies; ok to create empty stub here)

**Interfaces:**
- Produces: nav route `ANNOTATIONS = "annotations"` with a `composable(ANNOTATIONS)` block instantiating `AnnotationsListScreen`, and an extension of the `EPUB_READER` route to accept `?source=highlights`:

```
"epub_reader/{itemId}?startReadaloudAtSec={…}&openAtCfi={…}&startTocHref={…}&source={source}"
```

- [ ] **Step 1: Add route constants**

In `MainScreen.kt`:

```kotlin
private const val ANNOTATIONS = "annotations"
private const val EPUB_READER =
    "epub_reader/{itemId}?startReadaloudAtSec={startReadaloudAtSec}&openAtCfi={openAtCfi}&startTocHref={startTocHref}&source={source}"
```

Update every existing `navigate("epub_reader/…")` call site: normal opens omit `&source=…`; the elided opens append `&source=highlights`.

- [ ] **Step 2: Add the composable and wire drawer callback**

```kotlin
composable(ANNOTATIONS) {
    val vm: AnnotationsListViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    AnnotationsListScreen(
        state = state,
        onOpenDrawer = { scope.launch { drawerState.open() } },
        onBookClick = { serverId, itemId ->
            navController.navigate("epub_reader/${encode(itemId)}?source=highlights")
        },
    )
}
```

Pass `onAnnotationsSelected = { navController.navigate(ANNOTATIONS) }` to `RiffleNavigationDrawer`.

- [ ] **Step 3: Extend the reader composable to read `source`**

In the existing `composable(route = EPUB_READER, arguments = listOf(…))` block, add:

```kotlin
navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
```

Read the arg and pass to the ViewModel (via a `SavedStateHandle` — see how `openAtCfi` is already threaded).

- [ ] **Step 4: Instrumentation test — drawer entry opens the list**

`app/src/androidTest/kotlin/com/riffle/app/feature/annotations/AnnotationsNavigationTest.kt`:

```kotlin
@Test
fun tappingAnnotationsDrawerEntryOpensList() {
    composeRule.setContent { RiffleTheme { MainScreen() } }
    composeRule.onNodeWithContentDescription("Open navigation drawer").performClick()
    composeRule.onNodeWithText("Annotations").performClick()
    composeRule.onNodeWithText("No highlights yet.").assertIsDisplayed()
}
```

Should be tagged `@PhoneLayout` per the harness conventions.

- [ ] **Step 5: Run and verify**

```
make harness-test  # runs the phone AVD subset
```

Expected: new test PASSES; no other test regresses.

- [ ] **Step 6: Commit**

```
git add app
git commit -m "feat(annotations): wire drawer entry into nav graph"
```

---

## Task 6: `ReaderSource` enum + `HighlightsPublicationFactory`

**Files:**
- Create/finalize: `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/ReaderSource.kt`
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPublicationFactory.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPublicationFactoryTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class ReaderSource { FullBook, Highlights }

data class ChapterElision(
    val href: String,
    val title: String,
    val highlights: List<AnnotationEntity>, // pre-sorted by (spineIndex, progression, createdAt)
)

class HighlightsPublicationFactory @Inject constructor() {
    fun build(
        serverId: String,
        itemId: String,
        bookTitle: String?,
        chapters: List<ChapterElision>,
    ): Publication  // org.readium.r2.shared.publication.Publication
}
```

- [ ] **Step 1: Write the failing test**

```kotlin
class HighlightsPublicationFactoryTest {
    private val factory = HighlightsPublicationFactory()

    @Test
    fun spineOnlyIncludesChaptersWithHighlights() {
        val pub = factory.build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = "Dune",
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(hl("h1", "the spice must flow"))),
                ChapterElision("ch3.xhtml", "Chapter Three", listOf(hl("h2", "Fear is the mind-killer."))),
            ),
        )
        assertEquals(2, pub.readingOrder.size)
        assertEquals(listOf("Chapter One", "Chapter Three"), pub.tableOfContents.map { it.title })
    }

    @Test
    fun rendersHighlightsAndInlineNotesInCfiOrder() {
        val pub = factory.build(
            serverId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = listOf(
                ChapterElision(
                    "ch1.xhtml", "Chapter One",
                    listOf(
                        hl("h1", "first snippet", note = "my thought"),
                        hl("h2", "second snippet", note = null),
                    ),
                ),
            ),
        )
        val html = readChapterHtml(pub, index = 0)
        assertTrue(html.indexOf("first snippet") < html.indexOf("my thought"))
        assertTrue(html.indexOf("my thought")   < html.indexOf("second snippet"))
        assertTrue("<aside" in html, "notes render as <aside>")
    }

    @Test
    fun chapterTitleFallsBackToHrefBasenameThenChapterN() {
        val pub = factory.build(
            "S1", "B1", null,
            listOf(
                ChapterElision("ch2.xhtml", title = "ch2", listOf(hl("h1", "x"))),
                ChapterElision("",          title = "Chapter 2", listOf(hl("h2", "y"))),
            ),
        )
        assertEquals(listOf("ch2", "Chapter 2"), pub.tableOfContents.map { it.title })
    }
}
```

Helpers: `hl(id, snippet, note = null)` constructs `AnnotationEntity` with `TYPE_HIGHLIGHT` and increasing `spineIndex`/`progression`.

- [ ] **Step 2: Run test to verify FAIL**

```
./gradlew :app:testDebugUnitTest --tests com.riffle.app.feature.reader.highlights.HighlightsPublicationFactoryTest
```

Expected: FAIL.

- [ ] **Step 3: Implement `ReaderSource` + factory**

`ReaderSource.kt` — the enum.

`HighlightsPublicationFactory.kt` — build the Publication by constructing:
- a `Manifest` with `metadata = Metadata(title = bookTitle ?: "Annotations")`
- one `Link` per non-empty chapter with `href = "highlights/ch{index}.xhtml"`, `mediaType = MediaType.XHTML`
- an in-memory `Container` (custom `Container` impl backed by a `Map<String, ByteArray>`) that returns the synthesised XHTML for each spine href.
- HTML template per chapter:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{title}</title></head>
<body>
  <h1>{title}</h1>
  {for each highlight in cfi order}
    <p class="riffle-hl">{snippet}</p>
    {if note != null}
      <aside class="riffle-note">{note}</aside>
    {/if}
  {/for}
</body></html>
```

XML-escape all interpolations. Preserve the annotation `id` in a `data-ann-id` attribute on the `<p>` for the tap-to-edit dispatch layer to pick up in later tasks.

Verify: Readium's `Publication.Builder` (or the newer `Publication(manifest, container)` constructor — check the version in use) accepts an in-memory container. `EpubNavigatorFragment.createFactory(publication, …)` treats the synthesised publication identically to any other EPUB.

- [ ] **Step 4: Run test to verify PASS**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app
git commit -m "feat(annotations): synthesised Publication for elided reader"
```

---

## Task 7: EpubReaderViewModel — accept `source` and load synthesised Publication

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`
- Modify: DI binding site that provides the VM (if `SavedStateHandle` is the source, no binding change needed).
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelHighlightsSourceTest.kt`

**Interfaces:**
- Consumes: `AnnotationDao.observeForItem` (existing), `AnnotationsLibraryRepository` (for the chapter-title lookup — reuse the merged view), `HighlightsPublicationFactory` (Task 6), `LibraryItemDao.observeForServer(serverId).map { it.associateBy(::id) }` for the title lookup.
- Produces: nothing new externally; `EpubReaderViewModel.publication` now reflects the synthesised publication when `source == Highlights`.

- [ ] **Step 1: Write the failing test**

Assert that when the VM is instantiated with `source = Highlights`, the loaded `Publication`'s `readingOrder` contains one link per chapter with highlights (not the ABS EPUB's full spine). Use a fake `EpubRepository` that would fail if opened, plus stubbed `AnnotationDao` returning three highlights across two chapters.

- [ ] **Step 2: Verify FAIL**

```
./gradlew :app:testDebugUnitTest --tests com.riffle.app.feature.reader.EpubReaderViewModelHighlightsSourceTest
```

- [ ] **Step 3: Wire `source` into the VM**

Add `private val source: ReaderSource = savedStateHandle["source"]?.let(ReaderSource::valueOf) ?: ReaderSource.FullBook`.

Branch the publication-load coroutine:

```kotlin
when (source) {
    ReaderSource.FullBook -> loadAbsPublication(itemId)   // existing path
    ReaderSource.Highlights -> loadHighlightsPublication(serverId, itemId)
}
```

`loadHighlightsPublication`: read `annotationDao.getForItem(serverId, itemId)`, filter `type == TYPE_HIGHLIGHT && !deleted`, group by `chapterHref`, look up chapter titles from `LibraryItemDao`'s cached TOC (or the `chapterHref` fallback), build `List<ChapterElision>` (highlights sorted by `spineIndex, progression, createdAt`), call `HighlightsPublicationFactory.build(...)`, publish to `lifecycle.publication`.

- [ ] **Step 4: Verify PASS**

Same command. PASS.

- [ ] **Step 5: Commit**

```
git add app
git commit -m "feat(annotations): load synthesised publication in Highlights mode"
```

---

## Task 8: Gate Progress Sync + Reading Session + highlight-creation

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelHighlightsSuppressionTest.kt`

**Interfaces:**
- Consumes: `source: ReaderSource` (Task 7).
- Produces: no external change; the three code paths become no-ops when `source == Highlights`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun progressSync_isNotStartedInHighlightsMode() { /* verify progress-sync coroutine never touches network */ }

@Test
fun readingSession_isNotOpenedInHighlightsMode() { /* verify session repo receives no open call */ }

@Test
fun createHighlight_isNoOpInHighlightsMode() = runTest {
    val store = FakeAnnotationStore()
    val vm = viewModelWithSource(ReaderSource.Highlights, annotationStore = store)
    vm.createHighlight(fakeLocator(), IntRect.Zero)
    advanceUntilIdle()
    assertEquals(0, store.createHighlightCalls)
}
```

- [ ] **Step 2: Verify FAIL** — `./gradlew :app:testDebugUnitTest --tests …HighlightsSuppressionTest`.

- [ ] **Step 3: Add the guards**

At the top of each entry point:

```kotlin
fun createHighlight(selectionLocator: Locator, anchorRect: IntRect) {
    if (source == ReaderSource.Highlights) return
    // existing body
}
```

Wherever the Reading Session is opened (locate via `grep -n "sessions" app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`), guard with the same check. Same for the Progress Sync starter (search for the ADR 0019 sync loop entry point).

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit**

```
git add app
git commit -m "feat(annotations): suppress sync/session/create in Highlights mode"
```

---

## Task 9: UI suppression — hide Readaloud + Chapter Nav Rail; add "Open in book"

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/HighlightActionsSheet.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` (expose `source` + `openInBook(annotationId)` navigation event)
- Test: extend `app/src/test/kotlin/com/riffle/app/feature/reader/HighlightActionsSheet*.kt` (create if missing) — Compose test verifying "Open in book" appears only in Highlights mode; VM test verifying the navigation event's payload.

**Interfaces:**
- Produces:

```kotlin
sealed interface ReaderNavEvent {
    data class OpenInSourceBook(val serverId: String, val itemId: String, val cfi: String) : ReaderNavEvent
    // existing events…
}
```

- [ ] **Step 1: Write the failing Compose test** — with `source = Highlights` the `HighlightActionsSheet` renders an "Open in book" row; with `source = FullBook` it does not. Verify the Readaloud toggle isn't visible in `EpubReaderScreen`'s top-bar when `source == Highlights`.

- [ ] **Step 2: Verify FAIL**.

- [ ] **Step 3: Implement**

`EpubReaderScreen.kt` — gate the Readaloud toggle composable and the `ChapterNavigationRail` composable on `state.source == ReaderSource.FullBook`.

`HighlightActionsSheet.kt` — accept a new `showOpenInBook: Boolean` parameter and add an "Open in book" row before "Delete".

`EpubReaderViewModel.kt` — add `fun openHighlightInSourceBook(annotationId: String)` that reads the annotation, and emits `ReaderNavEvent.OpenInSourceBook(serverId, itemId, cfi)`. The nav host handles the event by pop-ing the current reader and pushing `epub_reader/{itemId}?openAtCfi={cfi}` (Full-Book mode, no `source` param).

Failure mode wiring — `MainScreen.kt` catches an exception from the underlying open path and shows a snackbar `"Book not available"`. Since the reader open flow already resolves availability, existing failure paths suffice; just ensure no crash and that a snackbar surfaces. Add a small `SnackbarHost` if missing at the reader route level.

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit**

```
git add app
git commit -m "feat(annotations): hide Readaloud/Rail, add Open-in-book"
```

---

## Task 10: Per-device resume position in the elided reader

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsResumeStore.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsResumeStoreTest.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

**Interfaces:**
- Produces:

```kotlin
interface HighlightsResumeStore {
    suspend fun lastHighlightId(serverId: String, itemId: String): String?
    suspend fun setLastHighlightId(serverId: String, itemId: String, annotationId: String)
}
```

- [ ] **Step 1: Write the failing test**

Round-trip `set → last` for `(S1, B1)`; verify `(S1, B2)` is unaffected; verify `null` for an untouched key.

- [ ] **Step 2: Verify FAIL**.

- [ ] **Step 3: Implement**

Backed by a Preferences DataStore keyed `"highlights_resume_${serverId}_${itemId}"`. Inject into VM.

VM behaviour: on `source == Highlights` publication load, read `lastHighlightId(serverId, itemId)`; if present and still exists in the synthesised spine, `navigator.go(locatorForAnnotation(id))`. On every `currentLocator` update that carries a `data-ann-id`, call `setLastHighlightId(...)`. Do not sync anywhere.

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit**

```
git add app
git commit -m "feat(annotations): per-device resume position in Highlights mode"
```

---

## Self-Review Notes

- **Spec coverage:** ADR 0041's three decisions map to Tasks 6+7 (synthesised publication + mode flag), Task 8 (sync/session suppression), Task 5 (mode-flag not sibling screen). Glossary requirements: server-scoped list (Task 3 + active-server flatMap), sort by max updatedAt (Task 1 SQL + Task 2 sortedByDescending), empty state copy (Task 3 Step 5), highlight-count badge (Task 3 Step 5), text-only fallback (Task 2 test + Task 3), drawer entry position & icon (Task 4), Server Switcher swap (Task 3 second test), no PDF (Task 1 SQL filter + no changes to PDF reader), no in-view dedup (relies on existing `highlightOverlapsAtSamePosition`; no new task needed), Formatting Preferences shared (Task 7 falls out — same `(serverId, itemId)` key already used by full-book reader), TOC/Search/Cadence/Auto-Scroll/Immersive/Volume/Wake all reused (Task 7 no changes to their code paths).
- **Placeholder scan:** clean — every code step shows the code.
- **Type consistency:** `AnnotatedBook` used identically in Tasks 2 → 3; `ReaderSource` in Tasks 6 → 7 → 8 → 9; `BookHighlightSummary` in Tasks 1 → 2; `HighlightsPublicationFactory.build` signature stable in Tasks 6 → 7; `ChapterElision.title` (String) used in the fallback rules in Task 6's third test.
- **What's not a task:** Colour preservation (deferred per Q3); PDF annotations (deferred per Q11 — schema needs an ADR when the time comes); highlight dedup at render time (falls out — write-time dedup already enforced).
