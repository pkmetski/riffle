# Chapter Navigation Rail — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a thin persistent UI strip at the bottom of the EPUB reader that visualises all top-level chapters of the book as equal-width segments, with the current chapter highlighted and a position cursor drawn inside that segment showing how far through the chapter the reader is.

**Architecture:** A pure-domain generator (`RailSegmentGenerator.kt`) derives `List<RailSegment>` from the top-level `tocEntries` already in `EpubReaderViewModel`, and finds the active segment index from `currentLocatorHref`. The cursor position `railCursorPosition` is derived from `activeRailSegmentIndex`, the number of segments, and `currentLocatorProgression` (within-chapter 0..1) so the cursor always sits inside the highlighted segment. A Compose component (`ChapterNavigationRail.kt`) renders equal-width tappable segments with the active one highlighted and the cursor as a vertical line drawn at `railCursorPosition * railWidth`.

**Tech Stack:** Kotlin, Jetpack Compose, Readium Kotlin SDK (existing), JUnit 4 unit tests, Hilt instrumented integration tests, MockWebServer harness tests.

---

## File Map

| Action   | Path                                                                                                    | Responsibility                                         |
|----------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| Create   | `app/src/main/kotlin/com/riffle/app/feature/reader/RailSegment.kt`                                     | Data class for one rail segment                        |
| Create   | `app/src/main/kotlin/com/riffle/app/feature/reader/RailSegmentGenerator.kt`                            | `buildRailSegments` + `findActiveSegmentIndex` logic    |
| Create   | `app/src/main/kotlin/com/riffle/app/feature/reader/ChapterNavigationRail.kt`                           | Compose UI component                                   |
| Create   | `app/src/test/kotlin/com/riffle/app/feature/reader/RailSegmentGeneratorTest.kt`                        | Unit tests for generator logic                         |
| Modify   | `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`                             | Add `railSegments`, `activeRailSegmentIndex`, `currentLocatorProgression`, `railCursorPosition` flows |
| Modify   | `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`                               | Overlay `ChapterNavigationRail` at bottom of reader    |
| Modify   | `app/src/androidTest/assets/test.epub`                                                                 | Add three subchapters to Chapter 2                     |
| Modify   | `app/src/androidTest/kotlin/com/riffle/app/feature/reader/TocIntegrationTest.kt`                       | Add rail integration tests                             |
| Modify   | `app/src/androidTest/kotlin/com/riffle/app/harness/ReaderSemanticMatchers.kt`                         | Add `TAG_RAIL` and `assertRailActiveSegment`           |
| Modify   | `app/src/androidTest/kotlin/com/riffle/app/harness/EpubHarnessTest.kt`                                | Add harness test for rail active segment                |

---

## Task 1: Data class — `RailSegment`

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/RailSegment.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.riffle.app.feature.reader

data class RailSegment(
    val title: String,
    val href: String,
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/RailSegment.kt
git commit -m "feat: add RailSegment data class"
```

---

## Task 2: Domain logic — `RailSegmentGenerator`

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/RailSegmentGenerator.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/RailSegmentGeneratorTest.kt`

The rail always shows **all top-level chapters** as segments. `buildRailSegments` maps every top-level TOC entry to a `RailSegment`. `findActiveSegmentIndex` resolves the current chapter from the locator href, falling back from an exact match to a base-href (pre-fragment) match.

### 2a — Write failing unit tests

- [ ] **Step 1: Write the unit test file**

```kotlin
package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class RailSegmentGeneratorTest {

    private val chapter1 = TocEntry("Chapter 1: The Beginning", "chapter1.xhtml",
        listOf(TocEntry("1.1", "chapter1.xhtml#s1"), TocEntry("1.2", "chapter1.xhtml#s2")))
    private val chapter2 = TocEntry("Chapter 2: The Middle", "chapter2.xhtml",
        listOf(TocEntry("2.1", "chapter2.xhtml#s1"), TocEntry("2.2", "chapter2.xhtml#s2"), TocEntry("2.3", "chapter2.xhtml#s3")))
    private val chapter3 = TocEntry("Chapter 3: The End", "chapter3.xhtml")
    private val toc = listOf(chapter1, chapter2, chapter3)

    // ── buildRailSegments ──────────────────────────────────────────────────

    @Test
    fun `returns one segment per top-level chapter`() {
        val segments = buildRailSegments(toc)
        assertEquals(3, segments.size)
        assertEquals(RailSegment("Chapter 1: The Beginning", "chapter1.xhtml"), segments[0])
        assertEquals(RailSegment("Chapter 2: The Middle",    "chapter2.xhtml"), segments[1])
        assertEquals(RailSegment("Chapter 3: The End",       "chapter3.xhtml"), segments[2])
    }

    @Test
    fun `returns empty list for empty TOC`() {
        assertEquals(emptyList<RailSegment>(), buildRailSegments(emptyList()))
    }

    @Test
    fun `ignores subchapters — segments are always top-level`() {
        val segments = buildRailSegments(toc)
        assertEquals(3, segments.size)
    }

    // ── findActiveSegmentIndex ─────────────────────────────────────────────

    private val bookSegments = listOf(
        RailSegment("Chapter 1: The Beginning", "chapter1.xhtml"),
        RailSegment("Chapter 2: The Middle",    "chapter2.xhtml"),
        RailSegment("Chapter 3: The End",       "chapter3.xhtml"),
    )

    @Test
    fun `exact href match selects correct chapter`() {
        assertEquals(2, findActiveSegmentIndex(bookSegments, "chapter3.xhtml"))
    }

    @Test
    fun `fragment href falls back to base href match`() {
        // currentHref has a fragment (subchapter anchor), but segment hrefs are bare chapter hrefs
        assertEquals(1, findActiveSegmentIndex(bookSegments, "chapter2.xhtml#s3"))
        assertEquals(0, findActiveSegmentIndex(bookSegments, "chapter1.xhtml#s1"))
    }

    @Test
    fun `returns 0 when href matches no segment`() {
        assertEquals(0, findActiveSegmentIndex(bookSegments, "unknown.xhtml"))
    }

    @Test
    fun `returns 0 for empty segment list`() {
        assertEquals(0, findActiveSegmentIndex(emptyList(), "chapter1.xhtml"))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail (functions not yet defined)**

```bash
cd /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.RailSegmentGeneratorTest" 2>&1 | grep -E "FAILED|BUILD"
```

Expected: BUILD FAILED — unresolved references to `buildRailSegments`, `findActiveSegmentIndex`.

### 2b — Implement the generator

- [ ] **Step 3: Create the implementation**

```kotlin
package com.riffle.app.feature.reader

fun buildRailSegments(tocEntries: List<TocEntry>): List<RailSegment> =
    tocEntries.map { RailSegment(it.title, it.href) }

fun findActiveSegmentIndex(segments: List<RailSegment>, currentHref: String): Int {
    if (segments.isEmpty()) return 0
    val exact = segments.indexOfFirst { it.href == currentHref }
    if (exact >= 0) return exact
    val currentBase = currentHref.substringBefore('#')
    val baseMatch = segments.indexOfFirst { it.href.substringBefore('#') == currentBase }
    return if (baseMatch >= 0) baseMatch else 0
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.RailSegmentGeneratorTest" 2>&1 | grep -E "BUILD|PASSED|FAILED"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/RailSegmentGenerator.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/RailSegmentGeneratorTest.kt
git commit -m "feat: add RailSegmentGenerator with unit tests"
```

---

## Task 3: ViewModel — expose rail state

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

The ViewModel currently tracks `_currentLocatorHref`. We need to also track the reading progression (0..1 within the resource) and derive `railSegments`, `activeRailSegmentIndex`, and `railCursorPosition`.

- [ ] **Step 1: Add the new state fields and flows**

In `EpubReaderViewModel.kt`, make these changes:

1. Add `_currentLocatorProgression` backing field alongside `_currentLocatorHref`:

```kotlin
private val _currentLocatorProgression = MutableStateFlow(0f)
val currentLocatorProgression: StateFlow<Float> = _currentLocatorProgression
```

2. Update `onPositionChanged` to also capture progression:

```kotlin
fun onPositionChanged(locator: Locator) {
    lastLocator = locator
    _currentLocatorHref.value = locator.href.toString()
    _currentLocatorProgression.value = locator.locations.progression?.toFloat() ?: 0f
    viewModelScope.launch {
        epubRepository.saveReadingPosition(itemId, locator.toJSON().toString())
    }
}
```

3. Add the derived rail flows below the existing `tocEntries` StateFlow:

```kotlin
val railSegments: StateFlow<List<RailSegment>> = tocEntries
    .map { buildRailSegments(it) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

val activeRailSegmentIndex: StateFlow<Int> = combine(
    railSegments,
    currentLocatorHref,
) { segments, href ->
    if (href == null) 0 else findActiveSegmentIndex(segments, href)
}.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

// Cursor position within the rail (0..1). Derived from active segment + within-chapter
// progression so the cursor is always inside the highlighted (active) segment, regardless
// of whether chapter lengths match the equal-width segment layout.
val railCursorPosition: StateFlow<Float> = combine(
    activeRailSegmentIndex,
    railSegments,
    currentLocatorProgression,
) { activeIndex, segments, progression ->
    if (segments.isEmpty()) 0f
    else ((activeIndex + progression.coerceIn(0f, 1f)) / segments.size).coerceIn(0f, 1f)
}.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)
```

4. Add the necessary import at the top of the file:

```kotlin
import kotlinx.coroutines.flow.combine
```

- [ ] **Step 2: Build to confirm no errors**

```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat: expose railSegments, activeRailSegmentIndex, railCursorPosition from ViewModel"
```

---

## Task 4: UI — `ChapterNavigationRail` composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ChapterNavigationRail.kt`

The rail is a `Box` of fixed height (6 dp). It draws:
- Equal-width segment slots side-by-side
- Active segment filled with a highlighted colour; other segments with a dimmer background
- Thin divider lines between segments
- A narrow (2 dp) vertical cursor line drawn at `cursorPosition * railWidth` from the left edge
- A `testTag("chapter_navigation_rail")` for test targeting
- A `semantics { contentDescription = "Active rail segment: ${activeSegment.title}" }` on the root `Box`
- Tapping anywhere calculates the segment index from the tap x-offset and fires `onSegmentClick`

- [ ] **Step 1: Create the composable**

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ChapterNavigationRail(
    segments: List<RailSegment>,
    activeIndex: Int,
    cursorPosition: Float,
    onSegmentClick: (RailSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return

    val barColor    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val activeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
    val dividerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val cursorColor = MaterialTheme.colorScheme.primary

    val activeTitle = segments.getOrNull(activeIndex)?.title ?: ""

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .testTag("chapter_navigation_rail")
            .semantics { contentDescription = "Active rail segment: $activeTitle" }
            .pointerInput(segments) {
                detectTapGestures { offset ->
                    val idx = (offset.x / size.width * segments.size)
                        .toInt()
                        .coerceIn(0, segments.size - 1)
                    onSegmentClick(segments[idx])
                }
            }
            .drawWithCache {
                val n = segments.size
                val segW = size.width / n
                onDrawBehind {
                    drawRect(color = barColor)
                    drawRect(
                        color = activeColor,
                        topLeft = Offset(activeIndex * segW, 0f),
                        size = Size(segW, size.height),
                    )
                    for (i in 1 until n) {
                        val x = segW * i
                        drawLine(
                            color = dividerColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    val cx = cursorPosition.coerceIn(0f, 1f) * size.width
                    drawLine(
                        color = cursorColor,
                        start = Offset(cx, 0f),
                        end = Offset(cx, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            },
    )
}
```

- [ ] **Step 2: Build to confirm no errors**

```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ChapterNavigationRail.kt
git commit -m "feat: add ChapterNavigationRail Compose component"
```

---

## Task 5: Wire the rail into `EpubReaderScreen`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

The rail overlays the reader content at the bottom of the screen inside the `is ReaderState.Ready` branch, guarded by `formattingPrefs.showChapterMap` (a user toggle, default `true`).

- [ ] **Step 1: Collect the new state flows and render the rail**

In `EpubReaderScreen`, inside the `is ReaderState.Ready` branch (after `EpubNavigatorView` and the `TocPanel`), add the rail:

```kotlin
is ReaderState.Ready -> {
    val locatorHref by viewModel.currentLocatorHref.collectAsState()
    val tocEntries by viewModel.tocEntries.collectAsState()
    val railSegments by viewModel.railSegments.collectAsState()
    val activeRailSegmentIndex by viewModel.activeRailSegmentIndex.collectAsState()
    val cursorPosition by viewModel.railCursorPosition.collectAsState()
    EpubNavigatorView(...)
    if (tocVisible) {
        TocPanel(...)
    }
    if (formattingPrefs.showChapterMap) {
        ChapterNavigationRail(
            segments = railSegments,
            activeIndex = activeRailSegmentIndex,
            cursorPosition = cursorPosition,
            onSegmentClick = viewModel::navigateToSegment,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
```

- [ ] **Step 2: Add `navigateToSegment` to `EpubReaderViewModel`**

In `EpubReaderViewModel.kt`, add this function right after `navigateToEntry`:

```kotlin
fun navigateToSegment(segment: RailSegment) {
    val pub = (state.value as? ReaderState.Ready)?.publication ?: return
    val link = pub.tableOfContents.findLinkByHref(segment.href) ?: return
    _navigationEvents.trySend(link)
}
```

- [ ] **Step 3: Build to confirm no errors**

```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat: wire ChapterNavigationRail into EpubReaderScreen"
```

---

## Task 6: Update test EPUB — add subchapters to Chapter 2

**Files:**
- Modify: `app/src/androidTest/assets/test.epub`

The integration and harness tests require Chapter 2 to have three named subchapters (s1, s2, s3) so that TOC parsing can be exercised. The rail itself still shows one segment per **top-level chapter**, so navigating to any subchapter of Chapter 2 correctly highlights the Chapter 2 segment.

- [ ] **Step 1: Extract the EPUB, update files, and repack**

```bash
cd /tmp
cp /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh/app/src/androidTest/assets/test.epub test_orig.epub
mkdir epub_work && cd epub_work
unzip ../test_orig.epub
```

- [ ] **Step 2: Update `OEBPS/chapter2.xhtml`** with three named `<h2 id="s1/s2/s3">` sections

- [ ] **Step 3: Update `OEBPS/nav.xhtml`** to add Chapter 2 subsections as nested `<li>` entries

- [ ] **Step 4: Repack and commit**

```bash
cd /tmp/epub_work
rm -f /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh/app/src/androidTest/assets/test.epub
zip -X /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh/app/src/androidTest/assets/test.epub mimetype
zip -rg /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh/app/src/androidTest/assets/test.epub META-INF OEBPS
cd /tmp && rm -rf epub_work
```

```bash
cd /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh
git add app/src/androidTest/assets/test.epub
git commit -m "test: add subchapters to Chapter 2 in test EPUB for rail tests"
```

---

## Task 7: Integration test — rail segments from real EPUB

**Files:**
- Modify: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/TocIntegrationTest.kt`

Add two tests verifying that:
1. `buildRailSegments` returns one segment per top-level chapter (3 total)
2. `findActiveSegmentIndex` returns index 1 (Chapter 2) when the locator href is a subchapter fragment of chapter 2

- [ ] **Step 1: Add the tests at the end of `TocIntegrationTest`**

```kotlin
@Test
fun railSegmentsAreAllTopLevelChapters() = runTest {
    val pub = openTestEpub()
    val entries = pub.tableOfContents.toTocEntries()

    val segments = buildRailSegments(entries)
    assertEquals("Rail should have one segment per top-level chapter", 3, segments.size)
    assertTrue("Segment 0 href should contain 'chapter1'", segments[0].href.contains("chapter1"))
    assertTrue("Segment 1 href should contain 'chapter2'", segments[1].href.contains("chapter2"))
    assertTrue("Segment 2 href should contain 'chapter3'", segments[2].href.contains("chapter3"))
}

@Test
fun activeSegmentIsChapter2WhenLocatorIsInChapter2() = runTest {
    val pub = openTestEpub()
    val entries = pub.tableOfContents.toTocEntries()
    val segments = buildRailSegments(entries)

    // A subchapter href (with fragment) should still resolve to the parent chapter segment
    val activeIndex = findActiveSegmentIndex(segments, "chapter2.xhtml#s3")
    assertEquals("Chapter 2 (index 1) should be active for chapter2.xhtml#s3", 1, activeIndex)
}
```

- [ ] **Step 2: Confirm the tests compile**

```bash
./gradlew :app:assembleAndroidTest 2>&1 | grep -E "error:|BUILD"
```

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/riffle/app/feature/reader/TocIntegrationTest.kt
git commit -m "test: add rail integration tests"
```

---

## Task 8: Harness test — rail highlights active chapter after navigation

**Files:**
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/ReaderSemanticMatchers.kt`
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/EpubHarnessTest.kt`

### 8a — Add rail semantic matcher

- [ ] **Step 1: Add `TAG_RAIL`, `assertRailActiveSegment`, and `waitUntilRailActiveSegment` to `ReaderSemanticMatchers`**

```kotlin
const val TAG_RAIL = "chapter_navigation_rail"

fun ComposeTestRule.assertRailActiveSegment(titleSubstring: String) {
    val nodes = onAllNodes(
        hasContentDescription("Active rail segment: $titleSubstring", substring = true)
    ).fetchSemanticsNodes()
    if (nodes.isEmpty()) {
        throw AssertionError(
            "Expected navigation rail to have an active segment matching '$titleSubstring' " +
            "but no such segment was found"
        )
    }
}

fun ComposeTestRule.waitUntilRailActiveSegment(
    titleSubstring: String,
    timeoutMillis: Long = 15_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodes(
            hasContentDescription("Active rail segment: $titleSubstring", substring = true)
        ).fetchSemanticsNodes().isNotEmpty()
    }
}
```

> Note: `onAllNodes` is a member of the `SemanticsNodeInteractionsProvider` interface that `ComposeTestRule` implements. Do **not** add an import for it — call it directly as `this.onAllNodes(...)`.

### 8b — Add harness test

- [ ] **Step 2: Add the test to `EpubHarnessTest`**

```kotlin
@Test
fun railHighlightsActiveChapterAfterNavigation() {
    addServerAndBrowseLibrary()

    composeTestRule.waitUntil(timeoutMillis = 15_000) {
        composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
    assertReaderReady(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)

    // Navigate to Chapter 2 via the TOC panel
    composeTestRule.onNodeWithContentDescription("Table of Contents").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
        composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_TOC_PANEL).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithText("Section 2.3: Turning Point").performClick()

    composeTestRule.waitUntilInChapter("chapter2", timeoutMillis = 15_000)
    composeTestRule.assertNoErrorState()

    // Rail should highlight Chapter 2 as the active chapter
    composeTestRule.waitUntilRailActiveSegment("Chapter 2", timeoutMillis = 10_000)
    composeTestRule.assertRailActiveSegment("Chapter 2")
}
```

- [ ] **Step 3: Confirm the test compiles**

```bash
./gradlew :app:assembleAndroidTest 2>&1 | grep -E "error:|BUILD"
```

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/kotlin/com/riffle/app/harness/ReaderSemanticMatchers.kt \
        app/src/androidTest/kotlin/com/riffle/app/harness/EpubHarnessTest.kt
git commit -m "test: add harness test asserting rail highlights active chapter after navigation"
```

---

## Task 9: Run all unit tests + verify build

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | grep -E "BUILD|FAILED|tests were run"
```

Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 2: Run harness tests**

```bash
make harness-test 2>&1 | tail -30
```

Expected: all tests pass including the new `railHighlightsActiveChapterAfterNavigation` test.

- [ ] **Step 3: Mark issue 16 acceptance criteria checked in the PR description**

---

## Self-Review Checklist

### Spec coverage

| Acceptance criterion | Covered by |
|---|---|
| Rail visible at bottom while reading | Task 5 — `ChapterNavigationRail` overlaid at `Alignment.BottomCenter` inside `ReaderState.Ready`, guarded by `showChapterMap` (default `true`) |
| One segment per top-level chapter | Task 2 `buildRailSegments` maps all top-level TOC entries |
| Active segment highlighted; cursor shows chapter progression | Task 4 `ChapterNavigationRail` UI — active rect + cursor line at `railCursorPosition * width` |
| Navigation updates active segment | Task 3 — `activeRailSegmentIndex` derived from `currentLocatorHref` on every `onPositionChanged` call |
| Tapping a segment navigates to that chapter | Task 5 `navigateToSegment` in ViewModel |
| Toggle via Settings → Chapter Map | `showChapterMap` field in `FormattingPreferences` + switch in `FormattingPanel` |
| Unit tests: segment generation | Task 2 `RailSegmentGeneratorTest` |
| Unit tests: active segment calculation (including fragment fallback) | Task 2 `RailSegmentGeneratorTest` |
| Integration tests: real EPUB TOC → segments + active index | Task 7 `TocIntegrationTest` additions |
| Harness test: navigate to Chapter 2 → rail highlights Chapter 2 | Task 8 `EpubHarnessTest` |

### Implementation notes

- `buildRailSegments` ignores subchapters — segments are always the flat top-level list.
- `findActiveSegmentIndex` does an exact match first, then falls back to the base-href (pre-`#`) match so that subchapter fragment hrefs correctly resolve to their parent chapter segment.
- `railCursorPosition = (activeIndex + progression) / segments.size` places the cursor inside the highlighted segment's equal-width slot. With `progression` being within-chapter (0..1), the cursor is always within the active segment regardless of actual chapter lengths.
- `onAllNodes` in `ReaderSemanticMatchers` must **not** be imported — it is a member of `SemanticsNodeInteractionsProvider` (which `ComposeTestRule` implements) and is resolved as `this.onAllNodes(...)` inside the extension functions.
