# Continuous Mode Reading Estimates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix reading time estimates and the chapter-map rail cursor not updating in continuous scroll mode, add a CLAUDE.md guideline for three-mode awareness, and extract three repeated code patterns.

**Architecture:** A pure `computeTotalProgression()` function converts `(href, withinChapterProgression, railSegments)` into a book-wide 0–1 fraction using the same weights the rail already draws from. It is called from the continuous `onPositionChanged` lambda in `EpubReaderScreen.kt`, which gains a `rememberUpdatedState` capture for `railSegments` so the lambda never reads a stale list. The ViewModel receives a complete `Locator` and needs no changes.

**Tech Stack:** Kotlin, Jetpack Compose, Readium SDK (`Locator`, `DecorableNavigator`), JUnit 4

## Global Constraints

- Never call `./gradlew :app:connectedDebugAndroidTest` directly — use `make harness-test` for device tests.
- Run JVM tests with `./gradlew test` (not `:testDebugUnitTest`).
- `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before every Gradle invocation.
- Do not push to remote without explicit user permission.
- No `Co-Authored-By: Claude` trailers in commits.

---

### Task 1: `computeTotalProgression` — pure function + unit tests

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousModeUtils.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/ComputeTotalProgressionTest.kt`

**Interfaces:**
- Produces: `fun computeTotalProgression(href: String, progression: Float, segments: List<RailSegment>): Float?`
  - Returns `null` when `segments` is empty, total weight is 0, or `href` is not found in segments.
  - Returns a value in `[0f, 1f]` otherwise.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/riffle/app/feature/reader/ComputeTotalProgressionTest.kt`:

```kotlin
package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputeTotalProgressionTest {

    private fun seg(href: String, weight: Float) = RailSegment(title = href, href = href, weight = weight)

    @Test
    fun `returns null for empty segments`() {
        assertNull(computeTotalProgression("ch1.xhtml", 0.5f, emptyList()))
    }

    @Test
    fun `returns null when href not in segments`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        assertNull(computeTotalProgression("ch3.xhtml", 0.5f, segments))
    }

    @Test
    fun `returns null when total weight is zero`() {
        val segments = listOf(seg("ch1.xhtml", 0f), seg("ch2.xhtml", 0f))
        assertNull(computeTotalProgression("ch1.xhtml", 0.5f, segments))
    }

    @Test
    fun `first chapter at start`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        assertEquals(0f, computeTotalProgression("ch1.xhtml", 0f, segments)!!, 0.001f)
    }

    @Test
    fun `first chapter at end`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        assertEquals(0.5f, computeTotalProgression("ch1.xhtml", 1f, segments)!!, 0.001f)
    }

    @Test
    fun `last chapter at start`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        assertEquals(0.5f, computeTotalProgression("ch2.xhtml", 0f, segments)!!, 0.001f)
    }

    @Test
    fun `last chapter at end`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        assertEquals(1f, computeTotalProgression("ch2.xhtml", 1f, segments)!!, 0.001f)
    }

    @Test
    fun `mid-chapter of three equal-weight chapters`() {
        val segments = listOf(seg("a.xhtml", 1f), seg("b.xhtml", 1f), seg("c.xhtml", 1f))
        // b at 50% → (1 + 1*0.5) / 3 = 0.5
        assertEquals(0.5f, computeTotalProgression("b.xhtml", 0.5f, segments)!!, 0.001f)
    }

    @Test
    fun `weighted chapters — heavy first chapter`() {
        // ch1 weight=3, ch2 weight=1, total=4
        // ch2 at 0% → cumulativeWeight=3, result = 3/4 = 0.75
        val segments = listOf(seg("ch1.xhtml", 3f), seg("ch2.xhtml", 1f))
        assertEquals(0.75f, computeTotalProgression("ch2.xhtml", 0f, segments)!!, 0.001f)
    }

    @Test
    fun `single chapter at 50%`() {
        val segments = listOf(seg("only.xhtml", 2f))
        assertEquals(0.5f, computeTotalProgression("only.xhtml", 0.5f, segments)!!, 0.001f)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.riffle.app.feature.reader.ComputeTotalProgressionTest" 2>&1 | tail -20
```

Expected: compilation failure — `computeTotalProgression` not defined.

- [ ] **Step 3: Implement `computeTotalProgression`**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousModeUtils.kt`:

```kotlin
package com.riffle.app.feature.reader

/**
 * Computes a book-wide [0, 1] progression for continuous scroll mode using the same
 * per-chapter weights the chapter rail draws from (derived from Readium position counts).
 *
 * Returns null when [segments] is empty, total weight is zero, or [href] has no matching
 * segment.
 */
fun computeTotalProgression(
    href: String,
    progression: Float,
    segments: List<RailSegment>,
): Float? {
    val idx = segments.indexOfFirst { it.href == href }
    if (idx < 0) return null
    val totalWeight = segments.sumOf { it.weight.toDouble() }.toFloat()
    if (totalWeight == 0f) return null
    val cumulativeWeight = segments.take(idx).sumOf { it.weight.toDouble() }.toFloat()
    val chapterWeight = segments[idx].weight
    return (cumulativeWeight + chapterWeight * progression) / totalWeight
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.riffle.app.feature.reader.ComputeTotalProgressionTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousModeUtils.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/ComputeTotalProgressionTest.kt
git commit -m "feat(reader): computeTotalProgression for continuous mode"
```

---

### Task 2: Wire `totalProgression` into the continuous `onPositionChanged` lambda

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

**Interfaces:**
- Consumes: `computeTotalProgression(href, progression, segments)` from Task 1.
- The continuous `onPositionChanged` lambda (line 2132) gains a `totalProgression` field in the `Locator` JSON it constructs.

- [ ] **Step 1: Add `rememberUpdatedState` for `railSegments`**

`railSegments` is collected at line 671:
```kotlin
val railSegments by viewModel.railSegments.collectAsState()
```

This is in the outer `EpubReaderBody` composable scope — the `AndroidView` factory captures values at creation time, so the lambda could read a stale empty list before positions load. Add a `rememberUpdatedState` capture alongside the other `currentXxx` vals (around line 1054):

```kotlin
val currentRailSegments by rememberUpdatedState(railSegments)
```

- [ ] **Step 2: Update the continuous `onPositionChanged` lambda**

Find the factory block starting at line 2132. Replace:

```kotlin
view.onPositionChanged = { href, progression ->
    val locator = Locator.fromJSON(
        org.json.JSONObject()
            .put("href", href)
            .put("type", "application/xhtml+xml")
            .put("locations", org.json.JSONObject().put("progression", progression.toDouble()))
    )
    if (locator != null) onPositionChanged(locator)
}
```

With:

```kotlin
view.onPositionChanged = { href, progression ->
    val totalProg = computeTotalProgression(href, progression, currentRailSegments)
    val locations = org.json.JSONObject().put("progression", progression.toDouble())
    if (totalProg != null) locations.put("totalProgression", totalProg.toDouble())
    val locator = Locator.fromJSON(
        org.json.JSONObject()
            .put("href", href)
            .put("type", "application/xhtml+xml")
            .put("locations", locations)
    )
    if (locator != null) onPositionChanged(locator)
}
```

- [ ] **Step 3: Build to confirm no compilation errors**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run full JVM test suite to confirm no regressions**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, no new failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "fix(reader): compute totalProgression in continuous mode position callback"
```

---

### Task 3: Add CLAUDE.md reader mode guideline

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Append the guideline**

Add the following section to the end of `CLAUDE.md`:

```markdown
## Reader mode changes

The reader has three modes: paginated, vertical, and continuous.

Paginated and vertical both use Readium's EpubNavigatorFragment (scroll=false vs
scroll=true). Readium drives navigation, emits position updates, and populates Locator
fields automatically.

Continuous uses a custom ContinuousReaderView with a fully manual position pipeline.
Anything Readium provides for free to paginated/vertical must be explicitly computed
and threaded through the continuous onPositionChanged lambda in EpubReaderScreen.kt.

Any change that touches the reader — position tracking, navigation events, new ViewModel
state, UI driven by the current locator — must be verified to work in all three modes,
with particular attention to continuous: if paginated/vertical get something from Readium,
ask whether continuous needs to compute an equivalent.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(reader): add three-mode awareness guideline to CLAUDE.md"
```

---

### Task 4: Extract `goToContinuousLocator` helper

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

The block:
```kotlin
continuousViewRef.value?.navigateTo(
    locator.href.toString(),
    locator.locations.progression?.toFloat() ?: 0f,
)
return@collect
```
appears verbatim in three `LaunchedEffect` blocks: `serverLocatorEvents` (line 1387), `returnNavEvents` (line 1424), and `annotationNavigationEvents` (line 1452).

**Interfaces:**
- Produces local lambda: `val goToContinuous: suspend (Locator) -> Unit`  
  Navigates the continuous view to the locator if the view reference is non-null. Keeping it as a local lambda gives access to `continuousViewRef` without threading it as a parameter.

- [ ] **Step 1: Define the helper lambda**

Add directly below the `goAndSnapWithCover` lambda (around line 1419):

```kotlin
val goToContinuous: suspend (Locator) -> Unit = { locator ->
    continuousViewRef.value?.navigateTo(
        locator.href.toString(),
        locator.locations.progression?.toFloat() ?: 0f,
    )
}
```

- [ ] **Step 2: Replace all three occurrences**

**`serverLocatorEvents` (around line 1386):**

Replace:
```kotlin
if (isContinuous) {
    continuousViewRef.value?.navigateTo(
        locator.href.toString(),
        locator.locations.progression?.toFloat() ?: 0f,
    )
    return@collect
}
```
With:
```kotlin
if (isContinuous) {
    goToContinuous(locator)
    return@collect
}
```

**`returnNavEvents` (around line 1423):**

Replace:
```kotlin
if (isContinuous) {
    continuousViewRef.value?.navigateTo(
        locator.href.toString(),
        locator.locations.progression?.toFloat() ?: 0f,
    )
} else {
```
With:
```kotlin
if (isContinuous) {
    goToContinuous(locator)
} else {
```

**`annotationNavigationEvents` (around line 1451):**

Replace:
```kotlin
if (isContinuous) {
    continuousViewRef.value?.navigateTo(
        locator.href.toString(),
        locator.locations.progression?.toFloat() ?: 0f,
    )
} else {
```
With:
```kotlin
if (isContinuous) {
    goToContinuous(locator)
} else {
```

- [ ] **Step 3: Build and test**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | tail -10
./gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` for both, no new failures.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "refactor(reader): extract goToContinuous helper for repeated continuous nav pattern"
```

---

### Task 5: Extract `applyDecorationsWithClear` helper

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

The pattern:
```kotlin
withContext(Dispatchers.Main) {
    fragment.applyDecorations(emptyList(), group = "<group>")
    fragment.applyDecorations(<list>, group = "<group>")
}
```
appears in four places:
- Lines 1543–1544 (search, inside a loop)
- Lines 1590–1591 (readaloud, single decoration)
- Lines 1675–1682 (annotations)
- Lines 1711–1713 (annotation-notes)

**Interfaces:**
- Produces top-level private suspend extension on `DecorableNavigator`:
  `private suspend fun DecorableNavigator.applyDecorationsWithClear(decorations: List<Decoration>, group: String)`

- [ ] **Step 1: Add the helper**

Add as a private top-level function near the top of the file, alongside other private helpers (search for `private fun` or `private suspend fun` in `EpubReaderScreen.kt` to find a suitable location):

```kotlin
private suspend fun DecorableNavigator.applyDecorationsWithClear(
    decorations: List<Decoration>,
    group: String,
) {
    withContext(Dispatchers.Main) {
        applyDecorations(emptyList(), group = group)
        applyDecorations(decorations, group = group)
    }
}
```

- [ ] **Step 2: Replace all four occurrences**

**Search effect — inside the settle loop (around line 1542):**

Replace:
```kotlin
withContext(Dispatchers.Main) {
    fragment.applyDecorations(emptyList(), group = "search")
    fragment.applyDecorations(decorations, group = "search")
}
```
With:
```kotlin
fragment.applyDecorationsWithClear(decorations, group = "search")
```

**Readaloud effect (around line 1590):**

Replace:
```kotlin
withContext(Dispatchers.Main) {
    fragment.applyDecorations(emptyList(), group = "readaloud")
    fragment.applyDecorations(listOf(decoration), group = "readaloud")
}
```
With:
```kotlin
fragment.applyDecorationsWithClear(listOf(decoration), group = "readaloud")
```

**Annotations effect (around line 1681):**

Replace:
```kotlin
withContext(Dispatchers.Main) {
    fragment.applyDecorations(emptyList(), group = "annotations")
    fragment.applyDecorations(decorations, group = "annotations")
}
```
With:
```kotlin
fragment.applyDecorationsWithClear(decorations, group = "annotations")
```

**Annotation-notes effect (around line 1711):**

Replace:
```kotlin
withContext(Dispatchers.Main) {
    fragment.applyDecorations(emptyList(), group = "annotation-notes")
    fragment.applyDecorations(noteDecorations, group = "annotation-notes")
}
```
With:
```kotlin
fragment.applyDecorationsWithClear(noteDecorations, group = "annotation-notes")
```

Note: the initial single-apply calls (lines 1525–1526 for search, 1564–1565 for readaloud clear-only) are NOT the clear+reapply pattern — leave those unchanged.

- [ ] **Step 3: Build and test**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | tail -10
./gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` for both, no new failures.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "refactor(reader): extract applyDecorationsWithClear extension for repeated pattern"
```
