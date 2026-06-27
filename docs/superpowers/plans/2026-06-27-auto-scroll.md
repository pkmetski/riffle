# Auto-Scroll Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the pure-JVM, TDD-able foundation of Auto-Scroll (ADR 0037): the speed value, per-book persistence, the pause-trigger state machine, and the tick-emitter that drives the scroll surface. UI (Compose pill + top-bar icon), navigator integration (Continuous and Vertical), wake-lock + volume-key + Readaloud-mutex wiring, and AVD harness tests are scoped as a follow-on plan; they require an emulator and cannot be red-green-refactor-driven from JVM tests alone.

**Architecture:** A small pure-Kotlin `auto-scroll` package inside `core/domain` plus the existing `FormattingPreferences` extension. The controller is a state-machine that consumes a clock, emits scroll deltas, and exposes a single `dispatch(event)` API. Integration code (Android, Compose, Readium) will sit in `app/` in a follow-on plan and call into this domain core.

**Tech Stack:** Kotlin, JUnit 4 (the project's standard), Hilt for later DI wiring.

## Global Constraints

- Min API: 24 (Android 7.0); existing project floor.
- All new domain code lives in `core/domain` (pure Kotlin, no Android dependencies). Tests in `core/domain/src/test/kotlin/...`.
- Default WPM: 250. Range: 80–600. Step: 10. Values copied verbatim from ADR 0037.
- All new files end with a trailing newline.
- Commit messages: Conventional Commits prefix (`feat:`, `test:`, `refactor:`); no `Co-Authored-By: Claude` trailer; PR/commit titles match the repo's existing style.
- Never push without explicit user request.

---

### Task 1: AutoScrollSpeed value + constants

A small wrapper around the WPM Int that enforces the documented range and step. Pure value type.

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/autoscroll/AutoScrollSpeed.kt`
- Test:   `core/domain/src/test/kotlin/com/riffle/core/domain/autoscroll/AutoScrollSpeedTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class AutoScrollSpeed(val wpm: Int)`
  - `object AutoScrollSpeed.Companion { val MIN_WPM: Int, MAX_WPM: Int, STEP_WPM: Int, DEFAULT_WPM: Int, val Default: AutoScrollSpeed; fun of(wpm: Int): AutoScrollSpeed }`
  - `fun AutoScrollSpeed.nudge(by: Int): AutoScrollSpeed` — adds `by` (positive or negative) snapped to the step grid and clamped to [MIN, MAX].

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.riffle.core.domain.autoscroll

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoScrollSpeedTest {

    @Test
    fun `default is 250 wpm`() {
        assertEquals(250, AutoScrollSpeed.Default.wpm)
    }

    @Test
    fun `of clamps below MIN`() {
        assertEquals(80, AutoScrollSpeed.of(50).wpm)
        assertEquals(80, AutoScrollSpeed.of(0).wpm)
        assertEquals(80, AutoScrollSpeed.of(-100).wpm)
    }

    @Test
    fun `of clamps above MAX`() {
        assertEquals(600, AutoScrollSpeed.of(700).wpm)
        assertEquals(600, AutoScrollSpeed.of(Int.MAX_VALUE).wpm)
    }

    @Test
    fun `of snaps to STEP grid`() {
        assertEquals(250, AutoScrollSpeed.of(254).wpm)
        assertEquals(260, AutoScrollSpeed.of(255).wpm)
        assertEquals(250, AutoScrollSpeed.of(251).wpm)
    }

    @Test
    fun `nudge adds the step and clamps`() {
        val s = AutoScrollSpeed.of(250)
        assertEquals(260, s.nudge(10).wpm)
        assertEquals(240, s.nudge(-10).wpm)
        assertEquals(280, s.nudge(30).wpm)
    }

    @Test
    fun `nudge clamps at MAX`() {
        val s = AutoScrollSpeed.of(590)
        assertEquals(600, s.nudge(10).wpm)
        assertEquals(600, s.nudge(50).wpm)
    }

    @Test
    fun `nudge clamps at MIN`() {
        val s = AutoScrollSpeed.of(90)
        assertEquals(80, s.nudge(-10).wpm)
        assertEquals(80, s.nudge(-50).wpm)
    }

    @Test
    fun `nudge by non-step amount snaps to grid`() {
        val s = AutoScrollSpeed.of(250)
        // nudging by 13 lands at 263 → snap to 260
        assertEquals(260, s.nudge(13).wpm)
    }

    @Test
    fun `constants match ADR 0037`() {
        assertEquals(80, AutoScrollSpeed.MIN_WPM)
        assertEquals(600, AutoScrollSpeed.MAX_WPM)
        assertEquals(10, AutoScrollSpeed.STEP_WPM)
        assertEquals(250, AutoScrollSpeed.DEFAULT_WPM)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.autoscroll.AutoScrollSpeedTest"`
Expected: COMPILATION FAIL (`AutoScrollSpeed` does not exist).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.riffle.core.domain.autoscroll

@JvmInline
value class AutoScrollSpeed private constructor(val wpm: Int) {

    fun nudge(by: Int): AutoScrollSpeed = of(wpm + by)

    companion object {
        const val MIN_WPM: Int = 80
        const val MAX_WPM: Int = 600
        const val STEP_WPM: Int = 10
        const val DEFAULT_WPM: Int = 250

        val Default: AutoScrollSpeed = AutoScrollSpeed(DEFAULT_WPM)

        fun of(wpm: Int): AutoScrollSpeed {
            val clamped = wpm.coerceIn(MIN_WPM, MAX_WPM)
            val snapped = ((clamped + STEP_WPM / 2) / STEP_WPM) * STEP_WPM
            return AutoScrollSpeed(snapped.coerceIn(MIN_WPM, MAX_WPM))
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.autoscroll.AutoScrollSpeedTest"`
Expected: PASS — 8 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/autoscroll/AutoScrollSpeed.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/autoscroll/AutoScrollSpeedTest.kt
git commit -m "feat(auto-scroll): add AutoScrollSpeed value type"
```

---

### Task 2: AutoScrollState sealed type + transitions

The lifecycle of an auto-scroll session, as a state value. Tracks the running/idle/paused-by-X axis.

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/autoscroll/AutoScrollState.kt`
- Test:   `core/domain/src/test/kotlin/com/riffle/core/domain/autoscroll/AutoScrollStateTest.kt`

**Interfaces:**
- Consumes: `AutoScrollSpeed` (Task 1).
- Produces:
  - `sealed interface AutoScrollState { object Idle, data class Running(val speed: AutoScrollSpeed), data class Paused(val speed: AutoScrollSpeed, val cause: PauseCause) }`
  - `enum class PauseCause { AppBackgrounded, ScreenOff, ManualScroll, TextSelection, OrientationChange, PanelOpen, ReadaloudStarted }`
  - `val AutoScrollState.isActive: Boolean` — true for `Running` only.
  - `val AutoScrollState.speedOrNull: AutoScrollSpeed?`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.riffle.core.domain.autoscroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollStateTest {

    @Test
    fun `Idle is not active and has no speed`() {
        val s: AutoScrollState = AutoScrollState.Idle
        assertFalse(s.isActive)
        assertNull(s.speedOrNull)
    }

    @Test
    fun `Running is active and exposes speed`() {
        val s: AutoScrollState = AutoScrollState.Running(AutoScrollSpeed.Default)
        assertTrue(s.isActive)
        assertEquals(AutoScrollSpeed.Default, s.speedOrNull)
    }

    @Test
    fun `Paused is not active but retains speed`() {
        val speed = AutoScrollSpeed.of(300)
        val s: AutoScrollState = AutoScrollState.Paused(speed, PauseCause.PanelOpen)
        assertFalse(s.isActive)
        assertEquals(speed, s.speedOrNull)
    }

    @Test
    fun `every PauseCause exists`() {
        // Compile-time exhaustiveness — guards against accidental removal.
        val all = PauseCause.values().toSet()
        assertTrue(PauseCause.AppBackgrounded in all)
        assertTrue(PauseCause.ScreenOff in all)
        assertTrue(PauseCause.ManualScroll in all)
        assertTrue(PauseCause.TextSelection in all)
        assertTrue(PauseCause.OrientationChange in all)
        assertTrue(PauseCause.PanelOpen in all)
        assertTrue(PauseCause.ReadaloudStarted in all)
        assertEquals(7, all.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.autoscroll.AutoScrollStateTest"`
Expected: COMPILATION FAIL.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.riffle.core.domain.autoscroll

sealed interface AutoScrollState {
    object Idle : AutoScrollState
    data class Running(val speed: AutoScrollSpeed) : AutoScrollState
    data class Paused(val speed: AutoScrollSpeed, val cause: PauseCause) : AutoScrollState
}

enum class PauseCause {
    AppBackgrounded,
    ScreenOff,
    ManualScroll,
    TextSelection,
    OrientationChange,
    PanelOpen,
    ReadaloudStarted,
}

val AutoScrollState.isActive: Boolean
    get() = this is AutoScrollState.Running

val AutoScrollState.speedOrNull: AutoScrollSpeed?
    get() = when (this) {
        is AutoScrollState.Idle -> null
        is AutoScrollState.Running -> speed
        is AutoScrollState.Paused -> speed
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.autoscroll.AutoScrollStateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/autoscroll/AutoScrollState.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/autoscroll/AutoScrollStateTest.kt
git commit -m "feat(auto-scroll): add AutoScrollState sealed type + PauseCause"
```

---

### Task 3: AutoScrollReducer — pure state transitions

Pure state-transition function. No I/O, no clocks. Mirrors how Compose / Redux-style state machines test.

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/autoscroll/AutoScrollReducer.kt`
- Test:   `core/domain/src/test/kotlin/com/riffle/core/domain/autoscroll/AutoScrollReducerTest.kt`

**Interfaces:**
- Consumes: `AutoScrollState`, `AutoScrollSpeed`, `PauseCause` (Tasks 1–2).
- Produces:
  - `sealed interface AutoScrollEvent { object Start, object Stop, data class NudgeSpeed(val by: Int), data class Pause(val cause: PauseCause), object Resume, object ReachedEndOfBook }`
  - `fun reduce(state: AutoScrollState, event: AutoScrollEvent, defaultSpeed: AutoScrollSpeed): AutoScrollState`
- Behaviour:
  - **Start from Idle** → `Running(defaultSpeed)`.
  - **Start from Paused** → `Running(prev.speed)` (resume retains the speed).
  - **Start from Running** → no-op.
  - **Stop** → `Idle` from any state.
  - **NudgeSpeed in Running** → new Running with nudged speed.
  - **NudgeSpeed in Paused** → updates the retained speed (so resume reflects it).
  - **NudgeSpeed in Idle** → no-op.
  - **Pause in Running** → `Paused(speed, cause)`.
  - **Pause in Paused** → keeps the speed but updates the cause to the new one (most-recent cause wins).
  - **Pause in Idle** → no-op.
  - **Resume** = same as Start; Resume from Idle is a no-op (resume only meaningful if there's a paused state).
  - **ReachedEndOfBook** from Running → Idle (silent stop, per ADR 0037).
  - **ReachedEndOfBook** otherwise → no-op.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.riffle.core.domain.autoscroll

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoScrollReducerTest {

    private val def = AutoScrollSpeed.of(250)

    @Test
    fun `Start from Idle enters Running at default speed`() {
        val s = reduce(AutoScrollState.Idle, AutoScrollEvent.Start, def)
        assertEquals(AutoScrollState.Running(def), s)
    }

    @Test
    fun `Start from Paused resumes Running at the retained speed`() {
        val paused = AutoScrollState.Paused(AutoScrollSpeed.of(300), PauseCause.PanelOpen)
        val s = reduce(paused, AutoScrollEvent.Start, def)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(300)), s)
    }

    @Test
    fun `Start from Running is a no-op`() {
        val running = AutoScrollState.Running(AutoScrollSpeed.of(300))
        assertEquals(running, reduce(running, AutoScrollEvent.Start, def))
    }

    @Test
    fun `Stop from any state goes to Idle`() {
        assertEquals(AutoScrollState.Idle, reduce(AutoScrollState.Idle, AutoScrollEvent.Stop, def))
        assertEquals(
            AutoScrollState.Idle,
            reduce(AutoScrollState.Running(def), AutoScrollEvent.Stop, def),
        )
        assertEquals(
            AutoScrollState.Idle,
            reduce(AutoScrollState.Paused(def, PauseCause.PanelOpen), AutoScrollEvent.Stop, def),
        )
    }

    @Test
    fun `NudgeSpeed in Running updates the speed`() {
        val s = reduce(AutoScrollState.Running(def), AutoScrollEvent.NudgeSpeed(by = 10), def)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(260)), s)
    }

    @Test
    fun `NudgeSpeed in Paused updates retained speed but stays paused`() {
        val paused = AutoScrollState.Paused(def, PauseCause.PanelOpen)
        val s = reduce(paused, AutoScrollEvent.NudgeSpeed(by = -10), def)
        assertEquals(AutoScrollState.Paused(AutoScrollSpeed.of(240), PauseCause.PanelOpen), s)
    }

    @Test
    fun `NudgeSpeed in Idle is a no-op`() {
        assertEquals(
            AutoScrollState.Idle,
            reduce(AutoScrollState.Idle, AutoScrollEvent.NudgeSpeed(by = 10), def),
        )
    }

    @Test
    fun `Pause in Running goes to Paused`() {
        val s = reduce(
            AutoScrollState.Running(def),
            AutoScrollEvent.Pause(PauseCause.PanelOpen),
            def,
        )
        assertEquals(AutoScrollState.Paused(def, PauseCause.PanelOpen), s)
    }

    @Test
    fun `Pause in Paused updates the cause to most-recent`() {
        val paused = AutoScrollState.Paused(def, PauseCause.PanelOpen)
        val s = reduce(paused, AutoScrollEvent.Pause(PauseCause.ManualScroll), def)
        assertEquals(AutoScrollState.Paused(def, PauseCause.ManualScroll), s)
    }

    @Test
    fun `Pause in Idle is a no-op`() {
        assertEquals(
            AutoScrollState.Idle,
            reduce(AutoScrollState.Idle, AutoScrollEvent.Pause(PauseCause.PanelOpen), def),
        )
    }

    @Test
    fun `Resume from Paused goes to Running at retained speed`() {
        val paused = AutoScrollState.Paused(AutoScrollSpeed.of(300), PauseCause.PanelOpen)
        val s = reduce(paused, AutoScrollEvent.Resume, def)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(300)), s)
    }

    @Test
    fun `Resume from Idle is a no-op`() {
        assertEquals(AutoScrollState.Idle, reduce(AutoScrollState.Idle, AutoScrollEvent.Resume, def))
    }

    @Test
    fun `ReachedEndOfBook from Running goes silently to Idle`() {
        val s = reduce(AutoScrollState.Running(def), AutoScrollEvent.ReachedEndOfBook, def)
        assertEquals(AutoScrollState.Idle, s)
    }

    @Test
    fun `ReachedEndOfBook outside Running is a no-op`() {
        assertEquals(
            AutoScrollState.Idle,
            reduce(AutoScrollState.Idle, AutoScrollEvent.ReachedEndOfBook, def),
        )
        val paused = AutoScrollState.Paused(def, PauseCause.PanelOpen)
        assertEquals(paused, reduce(paused, AutoScrollEvent.ReachedEndOfBook, def))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.autoscroll.AutoScrollReducerTest"`
Expected: COMPILATION FAIL.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.riffle.core.domain.autoscroll

sealed interface AutoScrollEvent {
    object Start : AutoScrollEvent
    object Stop : AutoScrollEvent
    data class NudgeSpeed(val by: Int) : AutoScrollEvent
    data class Pause(val cause: PauseCause) : AutoScrollEvent
    object Resume : AutoScrollEvent
    object ReachedEndOfBook : AutoScrollEvent
}

fun reduce(
    state: AutoScrollState,
    event: AutoScrollEvent,
    defaultSpeed: AutoScrollSpeed,
): AutoScrollState = when (event) {
    AutoScrollEvent.Start, AutoScrollEvent.Resume -> when (state) {
        AutoScrollState.Idle -> if (event == AutoScrollEvent.Resume) state else AutoScrollState.Running(defaultSpeed)
        is AutoScrollState.Running -> state
        is AutoScrollState.Paused -> AutoScrollState.Running(state.speed)
    }
    AutoScrollEvent.Stop -> AutoScrollState.Idle
    is AutoScrollEvent.NudgeSpeed -> when (state) {
        AutoScrollState.Idle -> state
        is AutoScrollState.Running -> AutoScrollState.Running(state.speed.nudge(event.by))
        is AutoScrollState.Paused -> state.copy(speed = state.speed.nudge(event.by))
    }
    is AutoScrollEvent.Pause -> when (state) {
        AutoScrollState.Idle -> state
        is AutoScrollState.Running -> AutoScrollState.Paused(state.speed, event.cause)
        is AutoScrollState.Paused -> state.copy(cause = event.cause)
    }
    AutoScrollEvent.ReachedEndOfBook -> when (state) {
        is AutoScrollState.Running -> AutoScrollState.Idle
        else -> state
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.autoscroll.AutoScrollReducerTest"`
Expected: PASS — 14 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/autoscroll/AutoScrollReducer.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/autoscroll/AutoScrollReducerTest.kt
git commit -m "feat(auto-scroll): add AutoScrollReducer pure state transitions"
```

---

### Task 4: ScrollPace — WPM-to-px-per-second conversion

A pure conversion: WPM + layout context → vertical pixels per second. Reflow-stable (does not depend on the EPUB, only on the viewport's typography).

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/autoscroll/ScrollPace.kt`
- Test:   `core/domain/src/test/kotlin/com/riffle/core/domain/autoscroll/ScrollPaceTest.kt`

**Interfaces:**
- Consumes: `AutoScrollSpeed` (Task 1).
- Produces:
  - `data class LayoutContext(val wordsPerLine: Float, val lineHeightPx: Float)`
  - `fun pxPerSecond(speed: AutoScrollSpeed, layout: LayoutContext): Float`
- Formula: `pxPerSec = (wpm / 60) / (wordsPerLine / lineHeightPx)` = `(wpm / 60) * (lineHeightPx / wordsPerLine)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.riffle.core.domain.autoscroll

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class ScrollPaceTest {

    private fun nearly(expected: Float, actual: Float, eps: Float = 0.05f) {
        assert(abs(expected - actual) < eps) { "expected ≈$expected, got $actual" }
    }

    private val typicalLayout = LayoutContext(wordsPerLine = 9f, lineHeightPx = 28f)

    @Test
    fun `default 250 wpm in typical layout is roughly 13 px per second`() {
        // (250 / 60) * (28 / 9) ≈ 12.96 px/s
        nearly(12.96f, pxPerSecond(AutoScrollSpeed.Default, typicalLayout))
    }

    @Test
    fun `doubling wpm doubles px per second`() {
        val a = pxPerSecond(AutoScrollSpeed.of(150), typicalLayout)
        val b = pxPerSecond(AutoScrollSpeed.of(300), typicalLayout)
        nearly(2f * a, b)
    }

    @Test
    fun `larger line height increases px per second proportionally`() {
        val a = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(9f, 28f))
        val b = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(9f, 56f))
        nearly(2f * a, b)
    }

    @Test
    fun `more words per line decreases px per second proportionally`() {
        val a = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(9f, 28f))
        val b = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(18f, 28f))
        nearly(a / 2f, b)
    }

    @Test
    fun `zero words per line yields zero pace`() {
        assertEquals(0f, pxPerSecond(AutoScrollSpeed.Default, LayoutContext(0f, 28f)), 0.0001f)
    }

    @Test
    fun `zero line height yields zero pace`() {
        assertEquals(0f, pxPerSecond(AutoScrollSpeed.Default, LayoutContext(9f, 0f)), 0.0001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.autoscroll.ScrollPaceTest"`
Expected: COMPILATION FAIL.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.riffle.core.domain.autoscroll

data class LayoutContext(
    val wordsPerLine: Float,
    val lineHeightPx: Float,
)

fun pxPerSecond(speed: AutoScrollSpeed, layout: LayoutContext): Float {
    if (layout.wordsPerLine <= 0f || layout.lineHeightPx <= 0f) return 0f
    return (speed.wpm / 60f) * (layout.lineHeightPx / layout.wordsPerLine)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.autoscroll.ScrollPaceTest"`
Expected: PASS — 6 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/autoscroll/ScrollPace.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/autoscroll/ScrollPaceTest.kt
git commit -m "feat(auto-scroll): add WPM→px/s ScrollPace conversion"
```

---

### Task 5: ScrollDeltaAccumulator — sub-pixel accumulator

Fractional scroll deltas across frames; emits whole-pixel deltas when the accumulator crosses 1.0.

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/autoscroll/ScrollDeltaAccumulator.kt`
- Test:   `core/domain/src/test/kotlin/com/riffle/core/domain/autoscroll/ScrollDeltaAccumulatorTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `class ScrollDeltaAccumulator { fun advance(deltaSec: Float, pxPerSec: Float): Int; fun reset(); val accumulated: Float (visible for tests via internal access or via toString) }`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.riffle.core.domain.autoscroll

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollDeltaAccumulatorTest {

    @Test
    fun `under one pixel emits zero`() {
        val a = ScrollDeltaAccumulator()
        // 0.5 px/s * 1 s = 0.5 px → 0
        assertEquals(0, a.advance(deltaSec = 1f, pxPerSec = 0.5f))
    }

    @Test
    fun `crossing one pixel emits one pixel`() {
        val a = ScrollDeltaAccumulator()
        // 0.6 px/s * 1 s = 0.6, +0.6 = 1.2 → emit 1, remainder 0.2
        assertEquals(0, a.advance(1f, 0.6f))
        assertEquals(1, a.advance(1f, 0.6f))
    }

    @Test
    fun `whole-pixel jumps are emitted in one call`() {
        val a = ScrollDeltaAccumulator()
        // 13 px/s * 1 s = 13 → emit 13
        assertEquals(13, a.advance(1f, 13f))
    }

    @Test
    fun `fractional remainder accumulates over frames`() {
        val a = ScrollDeltaAccumulator()
        var total = 0
        repeat(60) { total += a.advance(deltaSec = 1f / 60f, pxPerSec = 13f) }
        // 60 * (13/60) = 13 — full pixel total after one second of 60Hz ticks
        assertEquals(13, total)
    }

    @Test
    fun `reset zeroes the accumulator`() {
        val a = ScrollDeltaAccumulator()
        a.advance(1f, 0.6f)
        a.reset()
        assertEquals(0, a.advance(1f, 0.5f))
    }

    @Test
    fun `zero pxPerSec emits nothing`() {
        val a = ScrollDeltaAccumulator()
        assertEquals(0, a.advance(1f, 0f))
    }

    @Test
    fun `negative deltaSec is treated as zero`() {
        val a = ScrollDeltaAccumulator()
        assertEquals(0, a.advance(-1f, 13f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.autoscroll.ScrollDeltaAccumulatorTest"`
Expected: COMPILATION FAIL.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.riffle.core.domain.autoscroll

class ScrollDeltaAccumulator {

    private var fractional: Float = 0f

    fun advance(deltaSec: Float, pxPerSec: Float): Int {
        if (deltaSec <= 0f || pxPerSec <= 0f) return 0
        fractional += deltaSec * pxPerSec
        if (fractional < 1f) return 0
        val whole = fractional.toInt()
        fractional -= whole.toFloat()
        return whole
    }

    fun reset() {
        fractional = 0f
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.autoscroll.ScrollDeltaAccumulatorTest"`
Expected: PASS — 7 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/autoscroll/ScrollDeltaAccumulator.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/autoscroll/ScrollDeltaAccumulatorTest.kt
git commit -m "feat(auto-scroll): add ScrollDeltaAccumulator"
```

---

### Task 6: FormattingPreferences.autoScrollWpm field

Extend the global preference so the speed persists like font size and line spacing.

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt`
- Test:   `core/domain/src/test/kotlin/com/riffle/core/domain/FormattingPreferencesAutoScrollTest.kt` (new)

**Interfaces:**
- Produces:
  - `FormattingPreferences.autoScrollWpm: Int`
  - `FormattingPreferences.Companion.DEFAULT_AUTO_SCROLL_WPM: Int = 250`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingPreferencesAutoScrollTest {

    @Test
    fun `default autoScrollWpm is 250`() {
        assertEquals(250, FormattingPreferences().autoScrollWpm)
        assertEquals(250, FormattingPreferences.DEFAULT_AUTO_SCROLL_WPM)
    }

    @Test
    fun `copy with new autoScrollWpm preserves other fields`() {
        val base = FormattingPreferences(fontSize = 1.3f)
        val updated = base.copy(autoScrollWpm = 300)
        assertEquals(1.3f, updated.fontSize, 0f)
        assertEquals(300, updated.autoScrollWpm)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.FormattingPreferencesAutoScrollTest"`
Expected: COMPILATION FAIL.

- [ ] **Step 3: Add field to the data class**

In `FormattingPreferences.kt`, add the field and default constant.

```kotlin
data class FormattingPreferences(
    val fontSize: Float = DEFAULT_FONT_SIZE,
    // ... existing fields ...
    val themeSchedule: ThemeSchedule = ThemeSchedule(),
    val autoScrollWpm: Int = DEFAULT_AUTO_SCROLL_WPM,
) {
    companion object {
        // ... existing constants ...
        const val DEFAULT_AUTO_SCROLL_WPM: Int = 250
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.FormattingPreferencesAutoScrollTest"`
Expected: PASS.

- [ ] **Step 5: Run the whole core:domain suite to ensure no regressions**

Run: `./gradlew :core:domain:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/FormattingPreferencesAutoScrollTest.kt
git commit -m "feat(formatting-prefs): add autoScrollWpm field"
```

---

### Task 7: BookFormattingOverrides.autoScrollWpm override

Extend the per-book override to thread the new field.

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/BookFormattingOverrides.kt`
- Modify: `core/domain/src/test/kotlin/com/riffle/core/domain/BookFormattingOverridesTest.kt`

**Interfaces:**
- Produces: `BookFormattingOverrides.autoScrollWpm: Int?`; `isEmpty` now considers this field; `applyTo` and `withChanges` thread it.

- [ ] **Step 1: Extend the existing test file with new cases (write the failing tests)**

Append to `BookFormattingOverridesTest.kt`:

```kotlin
    @Test
    fun `applyTo threads autoScrollWpm override`() {
        val effective = BookFormattingOverrides(autoScrollWpm = 320).applyTo(global)
        assertEquals(320, effective.autoScrollWpm)
    }

    @Test
    fun `applyTo falls back to global autoScrollWpm when override is null`() {
        val withGlobal = global.copy(autoScrollWpm = 180)
        val effective = BookFormattingOverrides().applyTo(withGlobal)
        assertEquals(180, effective.autoScrollWpm)
    }

    @Test
    fun `isEmpty considers autoScrollWpm override`() {
        assertFalse(BookFormattingOverrides(autoScrollWpm = 320).isEmpty)
    }

    @Test
    fun `withChanges records new autoScrollWpm when changed`() {
        val previous = global.copy(autoScrollWpm = 250)
        val new = previous.copy(autoScrollWpm = 320)
        val updated = BookFormattingOverrides().withChanges(previous, new)
        assertEquals(320, updated.autoScrollWpm)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.BookFormattingOverridesTest"`
Expected: COMPILATION FAIL (`autoScrollWpm` not on `BookFormattingOverrides`).

- [ ] **Step 3: Extend the override class**

Modify `BookFormattingOverrides.kt`:

```kotlin
data class BookFormattingOverrides(
    val fontSize: Float? = null,
    // ... existing fields ...
    val justifyText: Boolean? = null,
    val autoScrollWpm: Int? = null,
) {
    val isEmpty: Boolean
        get() = fontSize == null &&
            // ... existing checks ...
            justifyText == null &&
            autoScrollWpm == null

    fun applyTo(global: FormattingPreferences): FormattingPreferences = FormattingPreferences(
        // ... existing field mappings ...
        justifyText = justifyText ?: global.justifyText,
        themeSchedule = global.themeSchedule,
        autoScrollWpm = autoScrollWpm ?: global.autoScrollWpm,
    )

    fun withChanges(
        previous: FormattingPreferences,
        new: FormattingPreferences,
    ): BookFormattingOverrides = copy(
        // ... existing field mappings ...
        justifyText = if (new.justifyText != previous.justifyText) new.justifyText else justifyText,
        autoScrollWpm = if (new.autoScrollWpm != previous.autoScrollWpm) new.autoScrollWpm else autoScrollWpm,
    )
}
```

- [ ] **Step 4: Run the BookFormattingOverridesTest suite**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.BookFormattingOverridesTest"`
Expected: PASS.

- [ ] **Step 5: Run the whole core:domain suite**

Run: `./gradlew :core:domain:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/BookFormattingOverrides.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/BookFormattingOverridesTest.kt
git commit -m "feat(formatting-prefs): thread autoScrollWpm through book overrides"
```

---

## Out of scope for this plan (follow-on)

The pieces below need an AVD and Compose harness; they are not red-green-refactor-able from JVM tests, and belong in a separate plan (`2026-06-27-auto-scroll-integration.md`) once the foundation above is in.

- **AutoScrollController** (Android) — owns a coroutine `Flow<Long>` clock tick, holds `state` as a `StateFlow<AutoScrollState>`, calls the reducer on every dispatch.
- **DataStore persistence** in `FormattingPreferencesStoreImpl` + `BookFormattingPreferencesStoreImpl` — wire the new field through the proto/JSON layer with a migration that defaults to 250.
- **Compose UI** — `AutoScrollToggleIcon` (top-bar icon, conditional), `AutoScrollHudPill` (translucent bottom-right control), Formatting Preferences slider.
- **Continuous mode integration** — `ContinuousReaderView.scrollBy(deltaY)` driven by the ticker; reach end-of-book → `ReachedEndOfBook` event.
- **Vertical mode integration** — `VerticalAutoScrollAdapter` that reaches into the Readium WebView and drives `scrollBy`, calls `goForward()` on chapter end with a ~600ms pause-on-load.
- **VolumeNavigationController** — temporary remap to NudgeSpeed events while `state.isActive`.
- **Wake lock** — `MainActivity` holds a transient lock while `state.isActive`.
- **ReadaloudController mutex** — Readaloud start → `Pause(ReadaloudStarted)` and refuses Resume until Readaloud stops; Auto-scroll start → cancels Readaloud playback.
- **Anchor-on-reflow** — capture top-of-viewport text node when Formatting opens; restore on close; resume.
- **AVD harness tests** — `make harness-test`, mode-by-mode (Vertical + Continuous), chapter boundary, end-of-book, manual-scroll pause.

These tasks need a working app build to validate; without that, JVM-only TDD is not enough per `AGENTS.md`.

---

## Self-Review

- **Spec coverage:** Tasks 1–5 cover the load-bearing domain logic (speed value, state, transitions, conversion, accumulator). Tasks 6–7 thread the new pref through the existing per-book override pipeline. The follow-on section names every remaining piece from ADR 0037 so nothing is silently dropped.
- **Placeholder scan:** no TBDs; all code blocks complete; all gradle commands explicit.
- **Type consistency:** `AutoScrollSpeed`, `AutoScrollState`, `PauseCause`, `AutoScrollEvent`, `LayoutContext` are introduced exactly once and used consistently in later tasks. `reduce(state, event, defaultSpeed)` signature is referenced identically in Task 3 and in the follow-on section. `autoScrollWpm` field name is identical on both `FormattingPreferences` (Task 6) and the override (Task 7).
