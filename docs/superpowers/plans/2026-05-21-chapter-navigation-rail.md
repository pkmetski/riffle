# Chapter Navigation Rail — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a thin persistent UI strip at the bottom of the EPUB reader that visualises the current chapter's subchapter segments and the reader's exact position within them.

**Architecture:** A pure-domain generator (`RailSegmentGenerator.kt`) derives `List<RailSegment>` and the active segment index from the existing `tocEntries` + `currentLocatorHref` flows already in `EpubReaderViewModel`. The ViewModel exposes these as `StateFlow`s. A Compose component (`ChapterNavigationRail.kt`) renders equal-width tappable segments with the active one highlighted and a position cursor drawn at `progression * railWidth`.

**Tech Stack:** Kotlin, Jetpack Compose, Readium Kotlin SDK (existing), JUnit 4 unit tests, Hilt instrumented integration tests, MockWebServer harness tests.

---

## File Map

| Action   | Path                                                                                                    | Responsibility                                         |
|----------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| Create   | `app/src/main/kotlin/com/riffle/app/feature/reader/RailSegment.kt`                                     | Data class for one rail segment                        |
| Create   | `app/src/main/kotlin/com/riffle/app/feature/reader/RailSegmentGenerator.kt`                            | `buildRailSegments` + `findActiveSegmentIndex` logic    |
| Create   | `app/src/main/kotlin/com/riffle/app/feature/reader/ChapterNavigationRail.kt`                           | Compose UI component                                   |
| Create   | `app/src/test/kotlin/com/riffle/app/feature/reader/RailSegmentGeneratorTest.kt`                        | Unit tests for generator logic                         |
| Modify   | `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`                             | Add `railSegments`, `activeRailSegmentIndex`, `currentLocatorProgression` flows |
| Modify   | `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`                               | Overlay `ChapterNavigationRail` at bottom of reader    |
| Modify   | `app/src/androidTest/assets/test.epub`                                                                 | Add three subchapters to Chapter 2                     |
| Modify   | `app/src/androidTest/kotlin/com/riffle/app/feature/reader/TocIntegrationTest.kt`                       | Add rail integration tests for Chapter 2               |
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

### 2a — Write failing unit tests

- [ ] **Step 1: Write the unit test file**

```kotlin
package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class RailSegmentGeneratorTest {

    private val sec21 = TocEntry("Section 2.1: Rising Action", "chapter2.xhtml#s1")
    private val sec22 = TocEntry("Section 2.2: Conflict",      "chapter2.xhtml#s2")
    private val sec23 = TocEntry("Section 2.3: Turning Point", "chapter2.xhtml#s3")

    private val chapter1 = TocEntry("Chapter 1", "chapter1.xhtml",
        listOf(TocEntry("1.1", "chapter1.xhtml#s1"), TocEntry("1.2", "chapter1.xhtml#s2")))
    private val chapter2 = TocEntry("Chapter 2", "chapter2.xhtml", listOf(sec21, sec22, sec23))
    private val chapter3 = TocEntry("Chapter 3", "chapter3.xhtml")
    private val toc = listOf(chapter1, chapter2, chapter3)

    // ── buildRailSegments ──────────────────────────────────────────────────

    @Test
    fun `segments for chapter with subchapters returns children`() {
        val segments = buildRailSegments(toc, "chapter2.xhtml#s2")
        assertEquals(listOf(
            RailSegment("Section 2.1: Rising Action", "chapter2.xhtml#s1"),
            RailSegment("Section 2.2: Conflict",      "chapter2.xhtml#s2"),
            RailSegment("Section 2.3: Turning Point", "chapter2.xhtml#s3"),
        ), segments)
    }

    @Test
    fun `segments for chapter with no subchapters returns single segment`() {
        val segments = buildRailSegments(toc, "chapter3.xhtml")
        assertEquals(listOf(RailSegment("Chapter 3", "chapter3.xhtml")), segments)
    }

    @Test
    fun `segments when href is chapter root (no fragment)`() {
        val segments = buildRailSegments(toc, "chapter2.xhtml")
        assertEquals(3, segments.size)
    }

    @Test
    fun `segments for unknown href returns empty`() {
        val segments = buildRailSegments(toc, "unknown.xhtml")
        assertEquals(emptyList<RailSegment>(), segments)
    }

    // ── findActiveSegmentIndex ─────────────────────────────────────────────

    @Test
    fun `active segment for exact href match`() {
        val segments = listOf(
            RailSegment("Section 2.1: Rising Action", "chapter2.xhtml#s1"),
            RailSegment("Section 2.2: Conflict",      "chapter2.xhtml#s2"),
            RailSegment("Section 2.3: Turning Point", "chapter2.xhtml#s3"),
        )
        assertEquals(2, findActiveSegmentIndex(segments, "chapter2.xhtml#s3"))
    }

    @Test
    fun `active segment defaults to 0 when no exact match`() {
        val segments = listOf(
            RailSegment("Section 2.1: Rising Action", "chapter2.xhtml#s1"),
            RailSegment("Section 2.2: Conflict",      "chapter2.xhtml#s2"),
        )
        assertEquals(0, findActiveSegmentIndex(segments, "chapter2.xhtml"))
    }

    @Test
    fun `active segment for single segment list is always 0`() {
        val segments = listOf(RailSegment("Chapter 3", "chapter3.xhtml"))
        assertEquals(0, findActiveSegmentIndex(segments, "chapter3.xhtml"))
    }

    @Test
    fun `active segment for empty list returns 0`() {
        assertEquals(0, findActiveSegmentIndex(emptyList(), "chapter3.xhtml"))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail (functions not yet defined)**

```bash
cd /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.RailSegmentGeneratorTest" 2>&1 | grep -E "error CS|FAILED|BUILD"
```

Expected: BUILD FAILED — unresolved references to `buildRailSegments`, `findActiveSegmentIndex`.

### 2b — Implement the generator

- [ ] **Step 3: Create the implementation**

```kotlin
package com.riffle.app.feature.reader

fun buildRailSegments(tocEntries: List<TocEntry>, currentHref: String): List<RailSegment> {
    val currentBase = currentHref.substringBefore('#')
    val chapter = tocEntries.find { it.href.substringBefore('#') == currentBase }
        ?: return emptyList()
    return if (chapter.children.isEmpty()) {
        listOf(RailSegment(chapter.title, chapter.href))
    } else {
        chapter.children.map { RailSegment(it.title, it.href) }
    }
}

fun findActiveSegmentIndex(segments: List<RailSegment>, currentHref: String): Int {
    if (segments.isEmpty()) return 0
    val exact = segments.indexOfFirst { it.href == currentHref }
    return if (exact >= 0) exact else 0
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

The ViewModel currently tracks `_currentLocatorHref`. We need to also track the reading progression (0..1 within the resource) and derive `railSegments` + `activeRailSegmentIndex`.

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
val railSegments: StateFlow<List<RailSegment>> = combine(
    tocEntries,
    currentLocatorHref,
) { toc, href ->
    if (href == null) emptyList() else buildRailSegments(toc, href)
}.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

val activeRailSegmentIndex: StateFlow<Int> = combine(
    railSegments,
    currentLocatorHref,
) { segments, href ->
    if (href == null) 0 else findActiveSegmentIndex(segments, href)
}.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
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
git commit -m "feat: expose railSegments, activeRailSegmentIndex, currentLocatorProgression from ViewModel"
```

---

## Task 4: UI — `ChapterNavigationRail` composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ChapterNavigationRail.kt`

The rail is a `Box` of fixed height (6 dp). It draws:
- Equal-width tappable segment slots side-by-side
- Active segment filled with `MaterialTheme.colorScheme.primary`
- Other segments filled with `MaterialTheme.colorScheme.surfaceVariant` with 60% opacity
- A narrow (2 dp) vertical cursor line drawn at `cursorPosition * railWidth` from the left edge, using `MaterialTheme.colorScheme.onBackground`
- Thin (1 dp) gaps between segments drawn in the background color
- A `testTag("chapter_navigation_rail")` for test targeting
- The active segment additionally carries `semantics { contentDescription = "Active rail segment: ${segment.title}" }`

- [ ] **Step 1: Create the composable**

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    val cursorColor = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .testTag("chapter_navigation_rail")
            .drawWithContent {
                drawContent()
                // Cursor: vertical line at cursorPosition across the full rail width
                val x = cursorPosition.coerceIn(0f, 1f) * size.width
                drawLine(
                    color = cursorColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            segments.forEachIndexed { index, segment ->
                val isActive = index == activeIndex
                val segmentModifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 0.5.dp)
                    .background(if (isActive) activeColor else inactiveColor)
                    .clickable { onSegmentClick(segment) }
                    .then(
                        if (isActive) Modifier.semantics {
                            contentDescription = "Active rail segment: ${segment.title}"
                        } else Modifier
                    )
                Box(modifier = segmentModifier)
            }
        }
    }
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

The rail overlays the reader content at the bottom of the screen. It is outside the `when (state)` block so it is always rendered once the reader is visible. Because it only has content when `railSegments` is non-empty (handled inside the composable), this is safe.

- [ ] **Step 1: Collect the new state flows and render the rail**

In `EpubReaderScreen`, inside the `is ReaderState.Ready` branch (after `EpubNavigatorView` and the `TocPanel`), add the rail. The full relevant section of the function should look like:

```kotlin
is ReaderState.Ready -> {
    val locatorHref by viewModel.currentLocatorHref.collectAsState()
    val tocEntries by viewModel.tocEntries.collectAsState()
    val railSegments by viewModel.railSegments.collectAsState()
    val activeRailSegmentIndex by viewModel.activeRailSegmentIndex.collectAsState()
    val cursorPosition by viewModel.currentLocatorProgression.collectAsState()
    EpubNavigatorView(
        state = s,
        formattingPrefs = formattingPrefs,
        onPositionChanged = viewModel::onPositionChanged,
        onNavigationEvents = viewModel.navigationEvents,
        modifier = Modifier
            .fillMaxSize()
            .testTag("reader_ready")
            .semantics {
                contentDescription = buildString {
                    append(locatorHref ?: "")
                    append(" theme:")
                    append(formattingPrefs.theme.name.lowercase())
                }
            },
    )
    if (tocVisible) {
        TocPanel(
            entries = tocEntries,
            activeHref = locatorHref,
            onEntryClick = viewModel::navigateToEntry,
            onDismiss = viewModel::closeToc,
        )
    }
    ChapterNavigationRail(
        segments = railSegments,
        activeIndex = activeRailSegmentIndex,
        cursorPosition = cursorPosition,
        onSegmentClick = viewModel::navigateToSegment,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
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

The harness and integration tests require Chapter 2 to have three subchapters (s1, s2, s3) so that the rail can show three segments and the test can navigate to segment 3.

- [ ] **Step 1: Extract the EPUB, update files, and repack**

```bash
cd /tmp
cp /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh/app/src/androidTest/assets/test.epub test_orig.epub
mkdir epub_work && cd epub_work
unzip ../test_orig.epub
```

- [ ] **Step 2: Update `OEBPS/chapter2.xhtml`**

Replace the contents of `/tmp/epub_work/OEBPS/chapter2.xhtml` with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Chapter 2: The Middle</title></head>
<body>
  <h1>Chapter 2: The Middle</h1>
  <h2 id="s1">Section 2.1: Rising Action</h2>
  <p>The second chapter continues the story. Conflict emerges and characters develop. The plot thickens and the stakes rise as the narrative progresses forward.</p>
  <p>Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.</p>
  <h2 id="s2">Section 2.2: Conflict</h2>
  <p>The conflict deepens as opposing forces collide. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum. Sed ut perspiciatis unde omnis iste natus error sit voluptatem accusantium doloremque laudantium.</p>
  <p>Totam rem aperiam, eaque ipsa quae ab illo inventore veritatis et quasi architecto beatae vitae dicta sunt explicabo. Nemo enim ipsam voluptatem quia voluptas sit aspernatur aut odit aut fugit, sed quia consequuntur magni dolores.</p>
  <h2 id="s3">Section 2.3: Turning Point</h2>
  <p>The turning point arrives. Neque porro quisquam est, qui dolorem ipsum quia dolor sit amet, consectetur, adipisci velit, sed quia non numquam eius modi tempora incidunt ut labore et dolore magnam aliquam quaerat voluptatem.</p>
  <p>Ut enim ad minima veniam, quis nostrum exercitationem ullam corporis suscipit laboriosam, nisi ut aliquid ex ea commodi consequatur. Quis autem vel eum iure reprehenderit qui in ea voluptate velit esse quam nihil molestiae consequatur.</p>
</body>
</html>
```

- [ ] **Step 3: Update `OEBPS/nav.xhtml` to add Chapter 2 subsections**

Replace the `<li>` for Chapter 2 in `/tmp/epub_work/OEBPS/nav.xhtml`:

```xml
      <li><a href="chapter2.xhtml">Chapter 2: The Middle</a>
        <ol>
          <li><a href="chapter2.xhtml#s1">Section 2.1: Rising Action</a></li>
          <li><a href="chapter2.xhtml#s2">Section 2.2: Conflict</a></li>
          <li><a href="chapter2.xhtml#s3">Section 2.3: Turning Point</a></li>
        </ol>
      </li>
```

- [ ] **Step 4: Repack the EPUB (mimetype must be first, uncompressed)**

```bash
cd /tmp/epub_work
rm -f /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh/app/src/androidTest/assets/test.epub
zip -X /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh/app/src/androidTest/assets/test.epub mimetype
zip -rg /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh/app/src/androidTest/assets/test.epub META-INF OEBPS
cd /tmp && rm -rf epub_work
```

- [ ] **Step 5: Commit**

```bash
cd /Users/plamen.kmetski/conductor/workspaces/riffle/riyadh
git add app/src/androidTest/assets/test.epub
git commit -m "test: add subchapters to Chapter 2 in test EPUB for rail tests"
```

---

## Task 7: Integration test — rail segments from real EPUB

**Files:**
- Modify: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/TocIntegrationTest.kt`

Add two tests verifying that after the EPUB update:
1. Chapter 2 has 3 subchapters in the parsed TOC
2. `buildRailSegments` + `findActiveSegmentIndex` return the expected values for a known href

- [ ] **Step 1: Add the tests at the end of `TocIntegrationTest`**

```kotlin
@Test
fun chapter2HasThreeSubsections() = runTest {
    val pub = openTestEpub()
    val entries = pub.tableOfContents.toTocEntries()
    val chapter2 = entries.find { it.href.contains("chapter2") }
    assertNotNull("Expected a chapter 2 entry", chapter2)
    assertEquals("Chapter 2 should have 3 subsections", 3, chapter2!!.children.size)
}

@Test
fun railSegmentsForChapter2SubsectionHref() = runTest {
    val pub = openTestEpub()
    val entries = pub.tableOfContents.toTocEntries()

    val segments = buildRailSegments(entries, "chapter2.xhtml#s3")
    assertEquals(3, segments.size)

    val activeIndex = findActiveSegmentIndex(segments, "chapter2.xhtml#s3")
    assertEquals("Segment 3 (index 2) should be active for chapter2.xhtml#s3", 2, activeIndex)
}
```

- [ ] **Step 2: Confirm the tests compile**

```bash
./gradlew :app:assembleAndroidTest 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/riffle/app/feature/reader/TocIntegrationTest.kt
git commit -m "test: add rail integration tests for Chapter 2 subsections"
```

---

## Task 8: Harness test — rail shows active segment after navigation

**Files:**
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/ReaderSemanticMatchers.kt`
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/EpubHarnessTest.kt`

### 8a — Add rail semantic matcher

- [ ] **Step 1: Add `TAG_RAIL` and `assertRailActiveSegment` to `ReaderSemanticMatchers`**

```kotlin
const val TAG_RAIL = "chapter_navigation_rail"

/**
 * Asserts the navigation rail has an active segment with the given title substring.
 * The active segment exposes its title via contentDescription "Active rail segment: <title>".
 */
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

/**
 * Polls until the rail's active segment contains [titleSubstring], or throws after [timeoutMillis].
 */
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

### 8b — Add harness test

- [ ] **Step 2: Add the test to `EpubHarnessTest`**

Add the following import at the top of `EpubHarnessTest.kt` (alongside existing imports):

```kotlin
import com.riffle.app.harness.ReaderSemanticMatchers.waitUntilRailActiveSegment
```

Add the test method:

```kotlin
@Test
fun railShowsSegment3AsActiveAfterNavigatingToChapter2Section3() {
    addServerAndBrowseLibrary()

    composeTestRule.waitUntil(timeoutMillis = 15_000) {
        composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
    assertReaderReady(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)

    // Navigate to Chapter 2 Section 3 via the TOC panel
    composeTestRule.onNodeWithContentDescription("Table of Contents").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
        composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_TOC_PANEL).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithText("Section 2.3: Turning Point").performClick()

    // Wait until the navigator reaches chapter 2
    composeTestRule.waitUntilInChapter("chapter2", timeoutMillis = 15_000)
    composeTestRule.assertNoErrorState()

    // Assert the rail highlights segment 3 (Section 2.3)
    composeTestRule.waitUntilRailActiveSegment("Section 2.3", timeoutMillis = 10_000)
    composeTestRule.assertRailActiveSegment("Section 2.3")
}
```

- [ ] **Step 3: Confirm the test compiles**

```bash
./gradlew :app:assembleAndroidTest 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/kotlin/com/riffle/app/harness/ReaderSemanticMatchers.kt \
        app/src/androidTest/kotlin/com/riffle/app/harness/EpubHarnessTest.kt
git commit -m "test: add harness test asserting rail active segment after TOC navigation"
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

Expected: all tests pass including the new `railShowsSegment3AsActiveAfterNavigatingToChapter2Section3` test.

- [ ] **Step 3: Mark issue 16 acceptance criteria checked in the PR description**

---

## Self-Review Checklist

### Spec coverage

| Acceptance criterion | Covered by |
|---|---|
| Rail visible at bottom while reading | Task 5 — `ChapterNavigationRail` overlaid at `Alignment.BottomCenter`, always rendered in `ReaderState.Ready` |
| Segments correspond to subchapters of current chapter | Task 2 `buildRailSegments`, Task 3 ViewModel flow |
| Active segment visually highlighted, position cursor | Task 4 `ChapterNavigationRail` UI |
| Advancing past subchapter boundary updates active segment | Task 3 — `activeRailSegmentIndex` is derived from `currentLocatorHref` which updates on every `onPositionChanged` call |
| No subchapters → single full-width segment + cursor | Task 2 `buildRailSegments` returns single-item list; Task 4 renders it the same way |
| Unit tests: segment generation from TOC subchapter data | Task 2 `RailSegmentGeneratorTest` |
| Unit tests: active segment calculation from current CFI position | Task 2 `RailSegmentGeneratorTest` (uses href, not CFI, matching production code) |
| Integration tests: load test EPUB TOC, generate rail segments, assert active index | Task 7 `TocIntegrationTest` additions |
| Harness tests: open EPUB → chapter 2 subchapter 3 → rail shows segment 3 | Task 8 `EpubHarnessTest` |

### No placeholder scan

All steps have concrete code. No "TBD" or "similar to above" patterns.

### Type consistency

- `RailSegment(title: String, href: String)` — used consistently in Task 1, 2, 3, 4, 5
- `buildRailSegments(tocEntries: List<TocEntry>, currentHref: String): List<RailSegment>` — consistent across Tasks 2, 3, 7
- `findActiveSegmentIndex(segments: List<RailSegment>, currentHref: String): Int` — consistent across Tasks 2, 3, 7
- `navigateToSegment(segment: RailSegment)` in ViewModel — consistent with Task 5 usage
- `viewModel.currentLocatorProgression` — consistent across Tasks 3, 5
