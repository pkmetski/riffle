# Reader Bookmarks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a corner-ribbon bookmark toggle to the EPUB reader so readers can mark and re-find their current page with a single tap.

**Architecture:** Reuse `AnnotationEntity` (no migration) with a new `TYPE_BOOKMARK` constant. Extend `AnnotationStore` with `createBookmark` / `observeBookmarks`, wire them into `EpubReaderViewModel` to derive page-level indicator state, and render a pentagon ribbon composable fixed at the viewport's top-right corner.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Readium R2, kotlinx.coroutines `combine` / `stateIn`

## Global Constraints

- No Room schema migration — `AnnotationEntity` schema is unchanged; only a new `TYPE_BOOKMARK = "BOOKMARK"` constant is added.
- Bookmarks are ABS-only — the corner indicator must not appear on Storyteller-only books or the Readaloud side. Use the existing `annotationsAvailable` StateFlow as the guard.
- Soft-delete only — removal calls `annotationStore.delete(id)` (tombstone), never a hard delete.
- Unit tests use `./gradlew test` (not `:testDebugUnitTest` — that misses pure-JVM modules).
- Do not `adb install` unless explicitly instructed.

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `core/database/src/main/kotlin/com/riffle/core/database/AnnotationEntity.kt` | Modify | Add `TYPE_BOOKMARK` constant |
| `core/domain/src/main/kotlin/com/riffle/core/domain/AnnotationStore.kt` | Modify | Add `observeBookmarks` / `createBookmark` to interface |
| `core/data/src/main/kotlin/com/riffle/core/data/AnnotationStoreImpl.kt` | Modify | Implement the two new methods |
| `core/data/src/test/kotlin/com/riffle/core/data/AnnotationStoreTest.kt` | Modify | Add `createBookmark` unit tests |
| `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` | Modify | Add `BookmarkPosition`, `_bookmarkPositions`, `isCurrentPageBookmarked`, `observeBookmarks()`, `toggleBookmark()` |
| `app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt` | Modify | Add `BookmarkIndicatorTest` class |
| `app/src/main/kotlin/com/riffle/app/feature/reader/CornerBookmarkIndicator.kt` | Create | Pentagon ribbon composable |
| `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` | Modify | Collect `isCurrentPageBookmarked`, render `CornerBookmarkIndicator` |

---

### Task 1: Store layer — TYPE_BOOKMARK + createBookmark / observeBookmarks

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/AnnotationEntity.kt`
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/AnnotationStore.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationStoreImpl.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/AnnotationStoreTest.kt`

**Interfaces:**
- Produces: `AnnotationStore.observeBookmarks(serverId, itemId): Flow<List<Annotation>>` and `AnnotationStore.createBookmark(serverId, itemId, cfi, textSnippet, chapterHref): Annotation`
- Produces: `AnnotationEntity.TYPE_BOOKMARK = "BOOKMARK"`

- [ ] **Step 1: Add TYPE_BOOKMARK to AnnotationEntity**

In `core/database/src/main/kotlin/com/riffle/core/database/AnnotationEntity.kt`, add inside the `companion object`:

```kotlin
companion object {
    const val TYPE_HIGHLIGHT = "HIGHLIGHT"
    const val TYPE_BOOKMARK = "BOOKMARK"
    const val COLOR_YELLOW = "yellow"
}
```

- [ ] **Step 2: Write the failing tests**

In `core/data/src/test/kotlin/com/riffle/core/data/AnnotationStoreTest.kt`, add after the existing tests:

```kotlin
@Test
fun `createBookmark persists a bookmark with correct type and empty color`() = runTest {
    val dao = FakeAnnotationDao()
    val store = buildStore(dao = dao, deviceId = "device-A", clock = { 9000L }, idGenerator = { "bm-1" })

    store.createBookmark(
        serverId = "abs1",
        itemId = "item1",
        cfi = "epubcfi(/6/4!/4/2)",
        textSnippet = "It seems increasingly likely",
        chapterHref = "chapter01.xhtml",
    )

    val saved = dao.getById("bm-1")!!
    assertEquals(AnnotationEntity.TYPE_BOOKMARK, saved.type)
    assertEquals("epubcfi(/6/4!/4/2)", saved.cfi)
    assertEquals("", saved.color)
    assertNull(saved.note)
    assertEquals("It seems increasingly likely", saved.textSnippet)
    assertEquals("chapter01.xhtml", saved.chapterHref)
    assertEquals(9000L, saved.createdAt)
    assertEquals(9000L, saved.updatedAt)
    assertEquals("device-A", saved.originDeviceId)
    assertEquals("device-A", saved.lastModifiedByDeviceId)
    assertFalse(saved.deleted)
}

@Test
fun `createBookmark returns the created annotation`() = runTest {
    val store = buildStore(idGenerator = { "bm-1" })

    val created = store.createBookmark("abs1", "item1", "epubcfi(/6/4!/4/2)", "snip", "c.xhtml")

    assertEquals("bm-1", created.id)
    assertEquals(AnnotationEntity.TYPE_BOOKMARK, created.type)
}

@Test
fun `observeBookmarks emits only bookmark-type annotations for the item`() = runTest {
    val dao = FakeAnnotationDao()
    var n = 0
    val store = buildStore(dao = dao, idGenerator = { "id-${n++}" })

    store.createHighlight("abs1", "item1", "epubcfi(a)", "h", "c")
    store.createBookmark("abs1", "item1", "epubcfi(b)", "snip", "c")
    store.createBookmark("abs1", "item2", "epubcfi(c)", "snip", "c")  // different item

    val list = store.observeBookmarks("abs1", "item1").first()
    assertEquals(1, list.size)
    assertEquals(AnnotationEntity.TYPE_BOOKMARK, list[0].type)
}

@Test
fun `delete tombstones a bookmark so it leaves observeBookmarks`() = runTest {
    val dao = FakeAnnotationDao()
    val store = buildStore(dao = dao, idGenerator = { "bm-1" })
    store.createBookmark("abs1", "item1", "epubcfi(/6/4!/4/2)", "snip", "c")

    store.delete("bm-1")

    assertTrue(store.observeBookmarks("abs1", "item1").first().isEmpty())
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test 2>&1 | tail -20
```

Expected: compilation error — `createBookmark` and `observeBookmarks` not defined yet.

- [ ] **Step 4: Add createBookmark / observeBookmarks to the AnnotationStore interface**

Replace `core/domain/src/main/kotlin/com/riffle/core/domain/AnnotationStore.kt` with:

```kotlin
package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * The primary, always-queryable Annotations store (ADR 0025). Local Room is the source of truth;
 * sync is a later, additive layer. v1 creates and reads Highlights and Bookmarks on the ABS side.
 */
interface AnnotationStore {

    /** Live, non-deleted highlights for an ABS Library Item, oldest first. */
    fun observeHighlights(serverId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted bookmarks for an ABS Library Item, oldest first. */
    fun observeBookmarks(serverId: String, itemId: String): Flow<List<Annotation>>

    /**
     * Create a Highlight at [cfi] (a CFI range) with the default colour, capturing the selected
     * [textSnippet] and its [chapterHref]. Mints a fresh UUID and stamps the current device + time.
     */
    suspend fun createHighlight(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        color: String = DEFAULT_COLOR,
    ): Annotation

    /**
     * Create a Bookmark at [cfi] (a CFI point = top-of-viewport position), capturing surrounding
     * [textSnippet] and [chapterHref] as re-anchoring fallback. Mints a fresh UUID.
     */
    suspend fun createBookmark(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
    ): Annotation

    /** Tombstone an annotation so the delete can later propagate to other devices. */
    suspend fun delete(id: String)

    companion object {
        const val DEFAULT_COLOR = "yellow"
    }
}
```

- [ ] **Step 5: Implement the two new methods in AnnotationStoreImpl**

In `core/data/src/main/kotlin/com/riffle/core/data/AnnotationStoreImpl.kt`, add after `observeHighlights`:

```kotlin
override fun observeBookmarks(serverId: String, itemId: String): Flow<List<Annotation>> =
    dao.observeForItem(serverId, itemId).map { rows ->
        rows.filter { it.type == AnnotationEntity.TYPE_BOOKMARK }.map { it.toDomain() }
    }
```

And after `createHighlight`, add:

```kotlin
override suspend fun createBookmark(
    serverId: String,
    itemId: String,
    cfi: String,
    textSnippet: String,
    chapterHref: String,
): Annotation {
    val deviceId = deviceIdStore.getOrCreate()
    val now = clock()
    val entity = AnnotationEntity(
        id = idGenerator(),
        serverId = serverId,
        itemId = itemId,
        type = AnnotationEntity.TYPE_BOOKMARK,
        cfi = cfi,
        color = "",
        note = null,
        textSnippet = textSnippet,
        chapterHref = chapterHref,
        createdAt = now,
        updatedAt = now,
        originDeviceId = deviceId,
        lastModifiedByDeviceId = deviceId,
        deleted = false,
    )
    dao.upsert(entity)
    return entity.toDomain()
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all tests pass (including new bookmark tests).

- [ ] **Step 7: Commit**

```bash
git add core/database/src/main/kotlin/com/riffle/core/database/AnnotationEntity.kt \
        core/domain/src/main/kotlin/com/riffle/core/domain/AnnotationStore.kt \
        core/data/src/main/kotlin/com/riffle/core/data/AnnotationStoreImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/AnnotationStoreTest.kt
git commit -m "feat(annotations): TYPE_BOOKMARK + createBookmark/observeBookmarks in store layer"
```

---

### Task 2: ViewModel bookmark state + toggleBookmark

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `AnnotationStore.observeBookmarks`, `AnnotationStore.createBookmark`, `AnnotationStore.delete` (from Task 1)
- Consumes: `epubCfiToSpineIndex(cfi)`, `extractCfiDocPath(cfi)`, `cfiDocPathToProgression(docPath, html)` (all `internal` in the `reader` package — accessible from `EpubReaderViewModel`)
- Produces: `EpubReaderViewModel.isCurrentPageBookmarked: StateFlow<Boolean>` and `EpubReaderViewModel.toggleBookmark()`

- [ ] **Step 1: Write the failing tests**

At the bottom of `app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt`, add a new test class. This class extracts and tests the indicator-matching logic in isolation — the same pattern used for `ReadingProgressLabelSource` and `ReaderSyncOrchestrationTest` in the existing file.

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkIndicatorTest {

    // Extracted pure logic: given a list of BookmarkPositions and a current locator,
    // derive whether the current page is bookmarked.
    private data class BookmarkPosition(val id: String, val chapterHref: String, val progression: Double)

    private fun isCurrentPageBookmarked(
        positions: List<BookmarkPosition>,
        href: String?,
        progression: Float?,
    ): Boolean {
        if (href == null) return false
        return positions.any { bm ->
            bm.chapterHref == href &&
                (progression == null || kotlin.math.abs(bm.progression - progression) < 0.05)
        }
    }

    @Test
    fun `returns false when href is null`() {
        val bm = BookmarkPosition("id", "ch1.xhtml", 0.3)
        assertFalse(isCurrentPageBookmarked(listOf(bm), href = null, progression = 0.3f))
    }

    @Test
    fun `returns false when no bookmarks`() {
        assertFalse(isCurrentPageBookmarked(emptyList(), "ch1.xhtml", 0.3f))
    }

    @Test
    fun `returns true when href matches and progression is within eps`() {
        val bm = BookmarkPosition("id", "ch1.xhtml", 0.30)
        assertTrue(isCurrentPageBookmarked(listOf(bm), "ch1.xhtml", 0.31f))
    }

    @Test
    fun `returns false when href matches but progression is outside eps`() {
        val bm = BookmarkPosition("id", "ch1.xhtml", 0.10)
        assertFalse(isCurrentPageBookmarked(listOf(bm), "ch1.xhtml", 0.20f))
    }

    @Test
    fun `returns false when progression matches but href differs`() {
        val bm = BookmarkPosition("id", "ch1.xhtml", 0.30)
        assertFalse(isCurrentPageBookmarked(listOf(bm), "ch2.xhtml", 0.30f))
    }

    @Test
    fun `returns true when progression is null (unknown position matches any page in chapter)`() {
        val bm = BookmarkPosition("id", "ch1.xhtml", 0.50)
        assertTrue(isCurrentPageBookmarked(listOf(bm), "ch1.xhtml", progression = null))
    }

    @Test
    fun `matches the first bookmark whose href and progression align`() {
        val bms = listOf(
            BookmarkPosition("id1", "ch1.xhtml", 0.10),
            BookmarkPosition("id2", "ch1.xhtml", 0.50),
        )
        // Near 0.10, not 0.50
        assertTrue(isCurrentPageBookmarked(bms, "ch1.xhtml", 0.11f))
    }
}
```

- [ ] **Step 2: Run tests to verify they pass** (this logic is pure, needs no new ViewModel code yet)

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL — the new `BookmarkIndicatorTest` passes immediately (it only tests the pure function, not VM wiring).

- [ ] **Step 3: Add BookmarkPosition + ViewModel state to EpubReaderViewModel**

In `EpubReaderViewModel.kt`, locate the `// ---- Annotations (ADR 0024 / 0025)` section (around line 298). After the `HighlightRender` data class and `_highlightRenders` declaration, add:

```kotlin
/** A persisted bookmark decoded to its chapter position for page-level indicator matching. */
data class BookmarkPosition(val id: String, val chapterHref: String, val progression: Double)

private val _bookmarkPositions = MutableStateFlow<List<BookmarkPosition>>(emptyList())

/**
 * True when one of this item's bookmarks falls on the reader's current page (chapter href +
 * within-chapter progression within [BOOKMARK_PAGE_EPS]).
 */
val isCurrentPageBookmarked: StateFlow<Boolean> = combine(
    _bookmarkPositions,
    _currentLocatorHref,
    _currentLocatorProgression,
) { positions, href, prog ->
    if (href == null) false
    else positions.any { bm ->
        bm.chapterHref == href &&
            (prog == null || kotlin.math.abs(bm.progression - prog) < BOOKMARK_PAGE_EPS)
    }
}.stateIn(viewModelScope, SharingStarted.Eagerly, false)
```

Also add the constant at the top of the file alongside `PARK_PAGE_EPS`:

```kotlin
private const val BOOKMARK_PAGE_EPS = 0.05   // ±5% within-chapter progression window
```

- [ ] **Step 4: Add observeBookmarks() to EpubReaderViewModel**

In the `// ---- Annotations` section, after `observeHighlights`, add:

```kotlin
private fun observeBookmarks(serverId: String) {
    viewModelScope.launch {
        combine(
            annotationStore.observeBookmarks(serverId, itemId),
            state,
        ) { annotations, st -> annotations to (st is ReaderState.Ready) }
            .collect { (annotations, ready) ->
                _bookmarkPositions.value =
                    if (!ready) emptyList() else annotations.mapNotNull { annotationToBookmarkPosition(it) }
            }
    }
}

private suspend fun annotationToBookmarkPosition(a: Annotation): BookmarkPosition? {
    val spineIndex = epubCfiToSpineIndex(a.cfi) ?: return null
    val html = readChapterHtml(spineIndex) ?: return null
    val docPath = extractCfiDocPath(a.cfi) ?: return null
    val progression = cfiDocPathToProgression(docPath, html) ?: return null
    return BookmarkPosition(a.id, a.chapterHref, progression)
}
```

- [ ] **Step 5: Call observeBookmarks from the ABS-guard init block**

Locate the existing block (around line 519–524):

```kotlin
// Annotations are ABS-side only (ADR 0024): available on a non-Storyteller server.
if (!isStorytellerServer && activeServer != null) {
    annotationServerId = activeServer.id
    _annotationsAvailable.value = true
    observeHighlights(activeServer.id)
}
```

Add `observeBookmarks(activeServer.id)` on the next line:

```kotlin
if (!isStorytellerServer && activeServer != null) {
    annotationServerId = activeServer.id
    _annotationsAvailable.value = true
    observeHighlights(activeServer.id)
    observeBookmarks(activeServer.id)
}
```

- [ ] **Step 6: Add toggleBookmark() to EpubReaderViewModel**

After the `createHighlight` function, add:

```kotlin
/**
 * Toggle the bookmark for the reader's current page. If the page is already bookmarked (within
 * [BOOKMARK_PAGE_EPS] progression), removes it; otherwise creates a new bookmark anchored to the
 * top-of-viewport CFI with the surrounding text as snippet.
 */
fun toggleBookmark() {
    val serverId = annotationServerId ?: return
    viewModelScope.launch {
        val locator = lastLocator ?: return@launch
        val href = locator.href.toString()
        val prog = locator.locations.progression ?: 0.0
        val existing = _bookmarkPositions.value.firstOrNull { bm ->
            bm.chapterHref == href && kotlin.math.abs(bm.progression - prog) < BOOKMARK_PAGE_EPS
        }
        if (existing != null) {
            annotationStore.delete(existing.id)
        } else {
            val cfi = locator.toPayload().ebookLocation
            val snippet = locator.text?.before?.take(200).orEmpty()
            annotationStore.createBookmark(
                serverId = serverId,
                itemId = itemId,
                cfi = cfi,
                textSnippet = snippet,
                chapterHref = href,
            )
        }
    }
}
```

- [ ] **Step 7: Run tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL — all existing tests still pass, new `BookmarkIndicatorTest` passes.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt
git commit -m "feat(annotations): bookmark state + toggleBookmark in EpubReaderViewModel"
```

---

### Task 3: CornerBookmarkIndicator composable + wire into EpubReaderScreen

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/CornerBookmarkIndicator.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

**Interfaces:**
- Consumes: `EpubReaderViewModel.isCurrentPageBookmarked: StateFlow<Boolean>` (from Task 2)
- Consumes: `EpubReaderViewModel.annotationsAvailable: StateFlow<Boolean>` (existing)
- Consumes: `EpubReaderViewModel.toggleBookmark()` (from Task 2)

- [ ] **Step 1: Create CornerBookmarkIndicator.kt**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/CornerBookmarkIndicator.kt`:

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val BookmarkActiveColor = Color(0xFFB5440E)
private val BookmarkIdleAlpha = 0.18f

/**
 * A pentagon bookmark ribbon pinned at the top-right corner of the reading area.
 * Idle: very low-opacity (ambient). Active: fills with [BookmarkActiveColor] + squish animation.
 * Hidden entirely when [isVisible] is false (non-ABS books, Storyteller-only).
 */
@Composable
fun CornerBookmarkIndicator(
    isBookmarked: Boolean,
    isVisible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isVisible) return

    val fillColor by animateColorAsState(
        targetValue = if (isBookmarked) BookmarkActiveColor
                      else BookmarkActiveColor.copy(alpha = BookmarkIdleAlpha),
        animationSpec = tween(durationMillis = 180),
        label = "bookmarkFill",
    )
    val scaleY by animateFloatAsState(
        targetValue = if (isBookmarked) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "bookmarkScaleY",
    )

    Box(
        modifier = modifier
            .size(width = 24.dp, height = 32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .semantics {
                role = Role.Button
                contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark this page"
            },
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 32.dp)) {
            // Pentagon bookmark shape: full-width rectangle tapering to a V at the bottom.
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width / 2f, size.height * 0.80f)
                lineTo(0f, size.height)
                close()
            }
            scale(scaleX = 1f, scaleY = scaleY, pivot = androidx.compose.ui.geometry.Offset(size.width / 2, 0f)) {
                drawPath(path, color = fillColor)
            }
        }
    }
}
```

- [ ] **Step 2: Wire CornerBookmarkIndicator into EpubReaderScreen**

In `EpubReaderScreen.kt`:

**2a.** Add the state collection near the other `collectAsState()` calls (around line 237–252):

```kotlin
val isCurrentPageBookmarked by viewModel.isCurrentPageBookmarked.collectAsState()
```

**2b.** Inside the outer `Box(modifier = Modifier.fillMaxSize())` (line 292), after the bottom stack `Column` block and before the `AnimatedVisibility` top bar block, add:

```kotlin
// Corner bookmark ribbon — always visible (not gated on chrome), ABS-only.
// Positioned just below the top bar using status-bar + fixed bar-height offset.
val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
val topBarOffset by animateDpAsState(
    targetValue = if (immersiveState.isImmersive) 0.dp else 64.dp,
    animationSpec = tween(durationMillis = 300),
    label = "bookmarkTopOffset",
)
CornerBookmarkIndicator(
    isBookmarked = isCurrentPageBookmarked,
    isVisible = annotationsAvailable && state is ReaderState.Ready,
    onToggle = viewModel::toggleBookmark,
    modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(top = statusBarTop + topBarOffset),
)
```

You will need to add this import at the top of `EpubReaderScreen.kt`:

```kotlin
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.calculateTopPadding
import androidx.compose.ui.unit.dp
```

> Note: some of these imports may already be present — only add the missing ones.

- [ ] **Step 3: Build to verify compilation**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL with no compilation errors.

- [ ] **Step 4: Run all tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL — all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/CornerBookmarkIndicator.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(annotations): corner-ribbon bookmark indicator in EPUB reader"
```
