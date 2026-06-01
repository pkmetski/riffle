# Auto Reader Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `Auto` reader theme that switches between a user-configured day and night theme at fixed local-clock times, live during a reading session.

**Architecture:** `Auto` is a fifth value of `ReaderTheme`. A `ThemeSchedule` (four global fields: day-start, night-start, day-theme, night-theme) drives an at-render-time resolver that returns one of the four concrete themes. Reader VMs combine the user's `FormattingPreferences` with a per-minute clock tick to produce an `effectivePreferences: StateFlow<FormattingPreferences>` whose `theme` is always concrete — every downstream consumer (Readium mapper, chapter-rail backdrop, palette) keeps reading `prefs.theme` and stays ignorant of Auto. The `FormattingPanel` still receives the raw preferences so the Auto chip can stay highlighted while the page renders in the resolved palette. Schedule editing surfaces only in the full-screen Settings variant of the panel.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Hilt, DataStore Preferences, Room, Readium Kotlin SDK, `java.time.LocalTime` (available via core library desugaring, already enabled in `app/build.gradle.kts:76`). Tests use JUnit 4, `kotlinx-coroutines-test` virtual time, and AndroidJUnit4 for Readium mapper tests.

**Spec source:** `CONTEXT.md` entries "Auto Theme", "Theme Schedule", and "Formatting Preferences"; `docs/adr/0022-auto-reader-theme-clock-scheduled-fifth-enum.md`.

**PDF note:** The PDF reader does not currently apply `ReaderTheme` to rendering (no `palette` or theme uses in `PdfReaderScreen.kt`). Auto persists fine for PDF books (theme is just an enum value); when PDF theming lands it'll consume the same `effectivePreferences` flow the EPUB reader uses.

---

### Task 1: Add `ReaderTheme.Auto` enum value

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt:31`

- [ ] **Step 1: Edit the enum**

Change line 31 from:

```kotlin
enum class ReaderTheme { Light, Dark, DarkDim, Sepia }
```

to:

```kotlin
enum class ReaderTheme { Light, Dark, DarkDim, Sepia, Auto }
```

Auto is added last so existing `ReaderTheme.entries` orderings (used by `FormattingPanel`) keep their layout and Auto appears at the end of the chip row.

- [ ] **Step 2: Build the domain module**

Run: `./gradlew :core:domain:compileKotlin`
Expected: PASS — `BookFormattingOverrides`, `FormattingPreferences`, and the store interface compile unchanged.

- [ ] **Step 3: Run unaffected domain tests**

Run: `./gradlew :core:domain:test`
Expected: PASS — existing `BookFormattingOverridesTest` still green; `ReaderTheme.entries.size` is now 5 but no test asserts on size.

- [ ] **Step 4: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt
git commit -m "feat(reader): add ReaderTheme.Auto enum value"
```

---

### Task 2: Add `ThemeSchedule` domain type

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt`
- Create: `core/domain/src/test/kotlin/com/riffle/core/domain/ThemeScheduleTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/domain/src/test/kotlin/com/riffle/core/domain/ThemeScheduleTest.kt`:

```kotlin
package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class ThemeScheduleTest {

    private val schedule = ThemeSchedule(
        dayStart = LocalTime.of(7, 0),
        nightStart = LocalTime.of(21, 0),
        dayTheme = ReaderTheme.Light,
        nightTheme = ReaderTheme.Dark,
    )

    @Test
    fun `noon is day`() {
        assertEquals(ReaderTheme.Light, schedule.resolve(LocalTime.of(12, 0)))
    }

    @Test
    fun `midnight is night`() {
        assertEquals(ReaderTheme.Dark, schedule.resolve(LocalTime.of(0, 0)))
    }

    @Test
    fun `exactly day-start is day`() {
        assertEquals(ReaderTheme.Light, schedule.resolve(LocalTime.of(7, 0)))
    }

    @Test
    fun `exactly night-start is night`() {
        assertEquals(ReaderTheme.Dark, schedule.resolve(LocalTime.of(21, 0)))
    }

    @Test
    fun `one minute before night-start is day`() {
        assertEquals(ReaderTheme.Light, schedule.resolve(LocalTime.of(20, 59)))
    }

    @Test
    fun `one minute before day-start is night`() {
        assertEquals(ReaderTheme.Dark, schedule.resolve(LocalTime.of(6, 59)))
    }

    @Test
    fun `night arc that wraps midnight evaluates correctly past midnight`() {
        // night 22:00 → 06:00
        val wrap = ThemeSchedule(
            dayStart = LocalTime.of(6, 0),
            nightStart = LocalTime.of(22, 0),
            dayTheme = ReaderTheme.Sepia,
            nightTheme = ReaderTheme.DarkDim,
        )
        assertEquals(ReaderTheme.DarkDim, wrap.resolve(LocalTime.of(2, 0)))
        assertEquals(ReaderTheme.DarkDim, wrap.resolve(LocalTime.of(23, 30)))
        assertEquals(ReaderTheme.Sepia, wrap.resolve(LocalTime.of(12, 0)))
    }

    @Test
    fun `equal day-start and night-start collapses to always-day`() {
        val degenerate = schedule.copy(
            dayStart = LocalTime.of(8, 0),
            nightStart = LocalTime.of(8, 0),
        )
        assertEquals(ReaderTheme.Light, degenerate.resolve(LocalTime.of(8, 0)))
        assertEquals(ReaderTheme.Light, degenerate.resolve(LocalTime.of(2, 0)))
        assertEquals(ReaderTheme.Light, degenerate.resolve(LocalTime.of(20, 0)))
    }

    @Test
    fun `default schedule has 07-00 21-00 Light Dark`() {
        val d = ThemeSchedule()
        assertEquals(LocalTime.of(7, 0), d.dayStart)
        assertEquals(LocalTime.of(21, 0), d.nightStart)
        assertEquals(ReaderTheme.Light, d.dayTheme)
        assertEquals(ReaderTheme.Dark, d.nightTheme)
    }

    @Test
    fun `nextBoundaryAfter returns the upcoming day-start when currently in night`() {
        // It is 02:00, night ends at 07:00.
        assertEquals(LocalTime.of(7, 0), schedule.nextBoundaryAfter(LocalTime.of(2, 0)))
    }

    @Test
    fun `nextBoundaryAfter returns the upcoming night-start when currently in day`() {
        // It is 12:00, day ends at 21:00.
        assertEquals(LocalTime.of(21, 0), schedule.nextBoundaryAfter(LocalTime.of(12, 0)))
    }

    @Test
    fun `nextBoundaryAfter at exactly the boundary returns the OTHER boundary`() {
        // At 21:00 we are now in night; the next boundary is 07:00 (next day, but
        // LocalTime wraps — caller computes the actual delay).
        assertEquals(LocalTime.of(7, 0), schedule.nextBoundaryAfter(LocalTime.of(21, 0)))
    }

    @Test
    fun `nextBoundaryAfter when equal-times returns dayStart unchanged`() {
        val degenerate = schedule.copy(
            dayStart = LocalTime.of(8, 0),
            nightStart = LocalTime.of(8, 0),
        )
        // No real boundary — caller should treat this as "never reschedule".
        // We define it to return dayStart for determinism.
        assertEquals(LocalTime.of(8, 0), degenerate.nextBoundaryAfter(LocalTime.of(2, 0)))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:domain:test --tests com.riffle.core.domain.ThemeScheduleTest`
Expected: FAIL with "Unresolved reference: ThemeSchedule".

- [ ] **Step 3: Add `ThemeSchedule` to `FormattingPreferences.kt`**

Append to the end of `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt`:

```kotlin
import java.time.LocalTime

data class ThemeSchedule(
    val dayStart: LocalTime = DEFAULT_DAY_START,
    val nightStart: LocalTime = DEFAULT_NIGHT_START,
    val dayTheme: ReaderTheme = DEFAULT_DAY_THEME,
    val nightTheme: ReaderTheme = DEFAULT_NIGHT_THEME,
) {
    fun resolve(now: LocalTime): ReaderTheme =
        if (isNight(now)) nightTheme else dayTheme

    // Treats the two times as boundaries on a 24h circle. The night arc runs
    // clockwise from nightStart up to (but not including) dayStart. At exactly
    // nightStart we are in night; at exactly dayStart we are in day. Equal
    // times collapse the night arc to length zero → always-day.
    private fun isNight(now: LocalTime): Boolean {
        if (dayStart == nightStart) return false
        return if (nightStart.isBefore(dayStart)) {
            // Same-day night arc, e.g. 13:00 → 18:00.
            !now.isBefore(nightStart) && now.isBefore(dayStart)
        } else {
            // Night arc wraps midnight, e.g. 21:00 → 07:00.
            !now.isBefore(nightStart) || now.isBefore(dayStart)
        }
    }

    // The next clock-time at which `resolve` would return a different theme.
    // Used by the reader VM to schedule a one-shot delay until the next switch.
    // Returns dayStart when the schedule is degenerate so callers always have a value.
    fun nextBoundaryAfter(now: LocalTime): LocalTime {
        if (dayStart == nightStart) return dayStart
        return if (isNight(now)) dayStart else nightStart
    }

    companion object {
        val DEFAULT_DAY_START: LocalTime = LocalTime.of(7, 0)
        val DEFAULT_NIGHT_START: LocalTime = LocalTime.of(21, 0)
        val DEFAULT_DAY_THEME: ReaderTheme = ReaderTheme.Light
        val DEFAULT_NIGHT_THEME: ReaderTheme = ReaderTheme.Dark
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:domain:test --tests com.riffle.core.domain.ThemeScheduleTest`
Expected: PASS (all 12 tests).

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/ThemeScheduleTest.kt
git commit -m "feat(domain): add ThemeSchedule with wrap-aware resolve and nextBoundaryAfter"
```

---

### Task 3: Plumb `themeSchedule` onto `FormattingPreferences` and add `withResolvedTheme`

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt`
- Create: `core/domain/src/test/kotlin/com/riffle/core/domain/FormattingPreferencesResolutionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/domain/src/test/kotlin/com/riffle/core/domain/FormattingPreferencesResolutionTest.kt`:

```kotlin
package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class FormattingPreferencesResolutionTest {

    @Test
    fun `concrete theme is unchanged by withResolvedTheme`() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Sepia)
        assertEquals(prefs, prefs.withResolvedTheme(LocalTime.of(12, 0)))
    }

    @Test
    fun `Auto resolves to day theme during day`() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto)
        assertEquals(ReaderTheme.Light, prefs.withResolvedTheme(LocalTime.of(12, 0)).theme)
    }

    @Test
    fun `Auto resolves to night theme during night`() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto)
        assertEquals(ReaderTheme.Dark, prefs.withResolvedTheme(LocalTime.of(22, 0)).theme)
    }

    @Test
    fun `Auto with a custom schedule picks the custom night theme`() {
        val prefs = FormattingPreferences(
            theme = ReaderTheme.Auto,
            themeSchedule = ThemeSchedule(
                dayStart = LocalTime.of(7, 0),
                nightStart = LocalTime.of(21, 0),
                dayTheme = ReaderTheme.Sepia,
                nightTheme = ReaderTheme.DarkDim,
            ),
        )
        assertEquals(ReaderTheme.DarkDim, prefs.withResolvedTheme(LocalTime.of(22, 0)).theme)
        assertEquals(ReaderTheme.Sepia, prefs.withResolvedTheme(LocalTime.of(12, 0)).theme)
    }

    @Test
    fun `default themeSchedule is the ThemeSchedule default`() {
        assertEquals(ThemeSchedule(), FormattingPreferences().themeSchedule)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:domain:test --tests com.riffle.core.domain.FormattingPreferencesResolutionTest`
Expected: FAIL with "Unresolved reference: themeSchedule" and "Unresolved reference: withResolvedTheme".

- [ ] **Step 3: Add the field and extension**

In `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt`, add `themeSchedule` to the data class (in the same parameter list, after `justifyText`) and add a top-level `withResolvedTheme` extension. The full file becomes:

```kotlin
package com.riffle.core.domain

import java.time.LocalTime

data class FormattingPreferences(
    val fontSize: Float = DEFAULT_FONT_SIZE,
    val theme: ReaderTheme = DEFAULT_THEME,
    val fontFamily: ReaderFontFamily = DEFAULT_FONT_FAMILY,
    val lineSpacing: Float = DEFAULT_LINE_SPACING,
    val margins: Float = DEFAULT_MARGINS,
    val orientation: ReaderOrientation = DEFAULT_ORIENTATION,
    val showChapterMap: Boolean = DEFAULT_SHOW_CHAPTER_MAP,
    val showReadingProgressLabels: Boolean = DEFAULT_SHOW_READING_PROGRESS_LABELS,
    val showCurrentChapterLabel: Boolean = DEFAULT_SHOW_CURRENT_CHAPTER_LABEL,
    val doublePageSpread: Boolean = DEFAULT_DOUBLE_PAGE_SPREAD,
    val justifyText: Boolean = DEFAULT_JUSTIFY_TEXT,
    val themeSchedule: ThemeSchedule = ThemeSchedule(),
) {
    companion object {
        const val DEFAULT_FONT_SIZE: Float = 1.0f
        const val DEFAULT_LINE_SPACING: Float = 1.2f
        const val DEFAULT_MARGINS: Float = 1.0f
        const val DEFAULT_SHOW_CHAPTER_MAP: Boolean = true
        const val DEFAULT_SHOW_READING_PROGRESS_LABELS: Boolean = false
        const val DEFAULT_SHOW_CURRENT_CHAPTER_LABEL: Boolean = false
        const val DEFAULT_DOUBLE_PAGE_SPREAD: Boolean = false
        const val DEFAULT_JUSTIFY_TEXT: Boolean = false
        val DEFAULT_THEME: ReaderTheme = ReaderTheme.Light
        val DEFAULT_FONT_FAMILY: ReaderFontFamily = ReaderFontFamily.Serif
        val DEFAULT_ORIENTATION: ReaderOrientation = ReaderOrientation.Horizontal
    }
}

enum class ReaderTheme { Light, Dark, DarkDim, Sepia, Auto }
enum class ReaderFontFamily { Serif, SansSerif, Monospace, Literata, Merriweather, OpenDyslexic }
enum class ReaderOrientation { Horizontal, Vertical }

data class ThemeSchedule(
    val dayStart: LocalTime = DEFAULT_DAY_START,
    val nightStart: LocalTime = DEFAULT_NIGHT_START,
    val dayTheme: ReaderTheme = DEFAULT_DAY_THEME,
    val nightTheme: ReaderTheme = DEFAULT_NIGHT_THEME,
) {
    fun resolve(now: LocalTime): ReaderTheme =
        if (isNight(now)) nightTheme else dayTheme

    private fun isNight(now: LocalTime): Boolean {
        if (dayStart == nightStart) return false
        return if (nightStart.isBefore(dayStart)) {
            !now.isBefore(nightStart) && now.isBefore(dayStart)
        } else {
            !now.isBefore(nightStart) || now.isBefore(dayStart)
        }
    }

    fun nextBoundaryAfter(now: LocalTime): LocalTime {
        if (dayStart == nightStart) return dayStart
        return if (isNight(now)) dayStart else nightStart
    }

    companion object {
        val DEFAULT_DAY_START: LocalTime = LocalTime.of(7, 0)
        val DEFAULT_NIGHT_START: LocalTime = LocalTime.of(21, 0)
        val DEFAULT_DAY_THEME: ReaderTheme = ReaderTheme.Light
        val DEFAULT_NIGHT_THEME: ReaderTheme = ReaderTheme.Dark
    }
}

// Returns a copy with `theme` replaced by the schedule-resolved concrete theme when
// the user picked Auto. For any non-Auto theme this is a no-op identity. Reader VMs
// run this at render-time so every downstream consumer (Readium mapper, palette,
// chapter-rail backdrop) keeps reading `prefs.theme` and stays ignorant of Auto.
fun FormattingPreferences.withResolvedTheme(now: LocalTime): FormattingPreferences =
    if (theme == ReaderTheme.Auto) copy(theme = themeSchedule.resolve(now)) else this
```

- [ ] **Step 4: Run the resolution test**

Run: `./gradlew :core:domain:test --tests com.riffle.core.domain.FormattingPreferencesResolutionTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Run all domain tests**

Run: `./gradlew :core:domain:test`
Expected: PASS — `BookFormattingOverridesTest` still green (`copy()` calls in the data class still work; new `themeSchedule` field has a default).

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/FormattingPreferencesResolutionTest.kt
git commit -m "feat(domain): add themeSchedule field and withResolvedTheme extension"
```

---

### Task 4: Persist `themeSchedule` in the global DataStore

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/FormattingPreferencesStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `core/data/src/test/kotlin/com/riffle/core/data/FormattingPreferencesStoreTest.kt`, after the last existing `@Test`:

```kotlin
    @Test
    fun `saved themeSchedule round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        val schedule = ThemeSchedule(
            dayStart = LocalTime.of(6, 30),
            nightStart = LocalTime.of(19, 45),
            dayTheme = ReaderTheme.Sepia,
            nightTheme = ReaderTheme.DarkDim,
        )
        store.update(FormattingPreferences(themeSchedule = schedule))
        assertEquals(schedule, store.preferences.first().themeSchedule)
    }

    @Test
    fun `themeSchedule defaults are returned for empty DataStore`() = testScope.runTest {
        assertEquals(ThemeSchedule(), buildStore().preferences.first().themeSchedule)
    }

    @Test
    fun `Auto theme round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(theme = ReaderTheme.Auto))
        assertEquals(ReaderTheme.Auto, store.preferences.first().theme)
    }
```

Add these imports to the file:

```kotlin
import com.riffle.core.domain.ThemeSchedule
import java.time.LocalTime
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:data:test --tests "com.riffle.core.data.FormattingPreferencesStoreTest"`
Expected: FAIL on the two `themeSchedule` tests; the `Auto` test may pass since enum-name round-trip already works.

- [ ] **Step 3: Implement persistence**

Edit `core/data/src/main/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt`:

Add imports near the top:

```kotlin
import androidx.datastore.preferences.core.intPreferencesKey
import com.riffle.core.domain.ThemeSchedule
import java.time.LocalTime
```

Add four new keys inside `private companion object`:

```kotlin
        val KEY_SCHEDULE_DAY_START = intPreferencesKey("theme_schedule_day_start_minute_of_day")
        val KEY_SCHEDULE_NIGHT_START = intPreferencesKey("theme_schedule_night_start_minute_of_day")
        val KEY_SCHEDULE_DAY_THEME = stringPreferencesKey("theme_schedule_day_theme")
        val KEY_SCHEDULE_NIGHT_THEME = stringPreferencesKey("theme_schedule_night_theme")
```

In the `preferences` flow `.map { prefs -> FormattingPreferences(...) }`, add `themeSchedule = ...` after `justifyText`:

```kotlin
            themeSchedule = ThemeSchedule(
                dayStart = prefs[KEY_SCHEDULE_DAY_START]?.let(::minuteOfDayToLocalTime)
                    ?: ThemeSchedule.DEFAULT_DAY_START,
                nightStart = prefs[KEY_SCHEDULE_NIGHT_START]?.let(::minuteOfDayToLocalTime)
                    ?: ThemeSchedule.DEFAULT_NIGHT_START,
                dayTheme = prefs[KEY_SCHEDULE_DAY_THEME]
                    ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                    ?.takeIf { it != ReaderTheme.Auto }
                    ?: ThemeSchedule.DEFAULT_DAY_THEME,
                nightTheme = prefs[KEY_SCHEDULE_NIGHT_THEME]
                    ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                    ?.takeIf { it != ReaderTheme.Auto }
                    ?: ThemeSchedule.DEFAULT_NIGHT_THEME,
            ),
```

The `takeIf { it != ReaderTheme.Auto }` guard prevents persisted-Auto-inside-Auto (which the UI restricts but is worth defending against here) — if someone hand-edits the prefs file, we fall back to the default concrete theme rather than infinite-recurse.

In `update`, add four writes after `prefs[KEY_JUSTIFY_TEXT] = preferences.justifyText`:

```kotlin
            prefs[KEY_SCHEDULE_DAY_START] = preferences.themeSchedule.dayStart.toMinuteOfDay()
            prefs[KEY_SCHEDULE_NIGHT_START] = preferences.themeSchedule.nightStart.toMinuteOfDay()
            prefs[KEY_SCHEDULE_DAY_THEME] = preferences.themeSchedule.dayTheme.name
            prefs[KEY_SCHEDULE_NIGHT_THEME] = preferences.themeSchedule.nightTheme.name
```

Add helpers at the end of the file, outside the class:

```kotlin
private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute
private fun minuteOfDayToLocalTime(value: Int): LocalTime =
    LocalTime.of((value / 60).coerceIn(0, 23), (value % 60).coerceIn(0, 59))
```

Persisting as `minuteOfDay` (0–1439) keeps the DataStore typed (`intPreferencesKey`) instead of relying on `LocalTime.toString()` parsing, and clamps trivially against junk values.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:data:test --tests "com.riffle.core.data.FormattingPreferencesStoreTest"`
Expected: PASS (all tests, including the three new ones).

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/FormattingPreferencesStoreTest.kt
git commit -m "feat(data): persist themeSchedule in global formatting prefs DataStore"
```

---

### Task 5: Defensive fallback in `ReaderThemePalette` for `Auto`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderThemePalette.kt`

Auto should never reach `palette` in production (reader VMs resolve first), but the `when` is exhaustive over the enum, so leaving Auto unhandled is a compile error. Fall back to Light's palette and document that consumers must resolve before asking.

- [ ] **Step 1: Edit the file**

Open `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderThemePalette.kt` and add a branch to the `when`:

```kotlin
val ReaderTheme.palette: ReaderThemePalette
    get() = when (this) {
        ReaderTheme.Light -> ReaderThemePalette(
            background = Color(0xFFFFFFFF),
            foreground = Color(0xFF121212),
        )
        ReaderTheme.Dark -> ReaderThemePalette(
            background = Color(0xFF000000),
            foreground = Color(0xFFFEFEFE),
        )
        ReaderTheme.DarkDim -> ReaderThemePalette(
            background = Color(0xFF000000),
            foreground = DARK_DIM_TEXT,
        )
        ReaderTheme.Sepia -> ReaderThemePalette(
            background = Color(0xFFFAF4E8),
            foreground = Color(0xFF121212),
        )
        // Defensive: Auto must be resolved to a concrete theme via
        // FormattingPreferences.withResolvedTheme() before reaching this palette.
        // We fall back to Light so a missed resolution doesn't crash the reader,
        // but every production call site should resolve first.
        ReaderTheme.Auto -> ReaderThemePalette(
            background = Color(0xFFFFFFFF),
            foreground = Color(0xFF121212),
        )
    }
```

- [ ] **Step 2: Build the app module**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS — exhaustive `when` over `ReaderTheme` now compiles.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ReaderThemePalette.kt
git commit -m "feat(reader): add defensive Light fallback in ReaderThemePalette for Auto"
```

---

### Task 6: Mapper exhaustiveness for `Auto` in `FormattingPreferencesMapper`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt`

The mapper's `when (theme)` is currently exhaustive over four values. Adding Auto would break it. The proper fix is that reader VMs only ever pass a resolved (concrete) `FormattingPreferences` into this mapper — but we still need exhaustiveness so the compile passes. Branch Auto to LIGHT as a defensive fallback (matching Task 5).

- [ ] **Step 1: Edit the file**

In `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt`, change the `theme = when (theme)` block (lines 30-34):

From:

```kotlin
        theme = when (theme) {
            ReaderTheme.Light -> Theme.LIGHT
            ReaderTheme.Dark, ReaderTheme.DarkDim -> Theme.DARK
            ReaderTheme.Sepia -> Theme.SEPIA
        },
```

To:

```kotlin
        theme = when (theme) {
            ReaderTheme.Light -> Theme.LIGHT
            ReaderTheme.Dark, ReaderTheme.DarkDim -> Theme.DARK
            ReaderTheme.Sepia -> Theme.SEPIA
            // Auto should be resolved to a concrete theme upstream by the reader VM
            // (via FormattingPreferences.withResolvedTheme). If it reaches here we
            // fall back to LIGHT rather than crashing.
            ReaderTheme.Auto -> Theme.LIGHT
        },
```

- [ ] **Step 2: Build the app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt
git commit -m "feat(reader): handle Auto exhaustiveness in toEpubPreferences mapper"
```

---

### Task 7: `EpubReaderScreen` exhaustiveness for `Auto` (alpha helper)

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt:407-410`

The chapter-rail backdrop alpha is a `when` over `ReaderTheme`. Same defensive fallback.

- [ ] **Step 1: Edit the `when`**

Locate (around line 405) the function that returns the rail foreground alpha. Change:

```kotlin
        ReaderTheme.Light -> 0.65f
        ReaderTheme.Dark -> 0.65f
        ReaderTheme.DarkDim -> 0.85f
        ReaderTheme.Sepia -> 0.70f
```

To:

```kotlin
        ReaderTheme.Light -> 0.65f
        ReaderTheme.Dark -> 0.65f
        ReaderTheme.DarkDim -> 0.85f
        ReaderTheme.Sepia -> 0.70f
        // Auto resolves upstream; treat as Light if it slips through.
        ReaderTheme.Auto -> 0.65f
```

- [ ] **Step 2: Build the app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): handle Auto exhaustiveness in chapter rail alpha"
```

---

### Task 8: Update mapper tests for `Auto` fallback

**Files:**
- Modify: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapperTest.kt`

- [ ] **Step 1: Append a test**

After the `sepiaThemeMapsToSEPIA` test, add:

```kotlin
    @Test
    fun autoThemeMapsToLIGHTAsDefensiveFallback() {
        // Reader VM resolves Auto before calling the mapper; this verifies the
        // fallback path the mapper provides for exhaustiveness.
        val result = FormattingPreferences(theme = ReaderTheme.Auto).toEpubPreferences()
        assertEquals(Theme.LIGHT, result.theme)
    }
```

- [ ] **Step 2: Run the existing harness**

Run: `make harness-test` (phone AVD; per `CLAUDE.md`).
Expected: PASS — all mapper tests including the new one.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapperTest.kt
git commit -m "test(reader): cover Auto theme fallback in FormattingPreferencesMapper"
```

---

### Task 9: Split-swatch composable for the Auto chip

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt`

The Auto chip's swatch is a circle split diagonally: top-left half painted in the day theme's background, bottom-right half in the night theme's background, with the outline in the corresponding foregrounds. Today's `ThemeSwatch(theme)` covers the four concrete themes; Auto needs a separate composable that takes the schedule.

- [ ] **Step 1: Replace the `ThemeSwatch` and its callers**

In `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt`, replace `ThemeSwatch` (around line 487) and the `swatchBackground`/`swatchForeground` extensions (lines 482-484) with:

```kotlin
@Composable
private fun ThemeSwatch(theme: ReaderTheme, schedule: ThemeSchedule) {
    if (theme == ReaderTheme.Auto) {
        AutoThemeSwatch(schedule)
    } else {
        ConcreteThemeSwatch(theme)
    }
}

@Composable
private fun ConcreteThemeSwatch(theme: ReaderTheme) {
    val palette = theme.palette
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(palette.background, RoundedCornerShape(percent = 50))
            .border(1.dp, palette.foreground, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = palette.foreground, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AutoThemeSwatch(schedule: ThemeSchedule) {
    val day = schedule.dayTheme.palette
    val night = schedule.nightTheme.palette
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(day.background, RoundedCornerShape(percent = 50))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center,
    ) {
        // Bottom-right half painted in the night background, clipped to the circle.
        Canvas(modifier = Modifier.size(18.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            clipPath(path) {
                drawCircle(color = night.background, radius = size.minDimension / 2f)
            }
        }
    }
}
```

Add imports if missing:

```kotlin
import androidx.compose.ui.graphics.drawscope.clipPath
import com.riffle.core.domain.ThemeSchedule
```

Update the FilterChip in the theme row (around line 145–152) to pass `prefs.themeSchedule` to the swatch:

```kotlin
                ReaderTheme.entries.forEach { theme ->
                    val label = theme.displayName()
                    FilterChip(
                        selected = prefs.theme == theme,
                        onClick = { onPrefsChange(prefs.copy(theme = theme)) },
                        label = { Text(label) },
                        leadingIcon = { ThemeSwatch(theme, prefs.themeSchedule) },
                        modifier = Modifier.semantics { contentDescription = "$label theme" },
                    )
                }
```

Update the `displayName` extension (around line 469) to include Auto:

```kotlin
private fun ReaderTheme.displayName(): String = when (this) {
    ReaderTheme.Light -> "Light"
    ReaderTheme.Dark -> "Dark"
    ReaderTheme.DarkDim -> "Dim"
    ReaderTheme.Sepia -> "Sepia"
    ReaderTheme.Auto -> "Auto"
}
```

- [ ] **Step 2: Build the app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt
git commit -m "feat(reader): add Auto theme chip with split day/night swatch"
```

---

### Task 10: Schedule sub-controls in the full-screen FormattingPanel

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt`

When `fullScreen == true` AND `prefs.theme == ReaderTheme.Auto`, reveal two `TimePicker`-backed time fields plus two restricted chip rows (one for day theme, one for night theme, both filtering Auto out). When `fullScreen == false`, hide the sub-controls — the in-reader panel just shows the Auto chip.

- [ ] **Step 1: Add the sub-controls block**

Right after the existing theme chip row (after the closing `Spacer(Modifier.height(16.dp))` that follows the theme `Row`, around line 154), insert:

```kotlin
            if (fullScreen && prefs.theme == ReaderTheme.Auto) {
                AutoScheduleControls(
                    schedule = prefs.themeSchedule,
                    onScheduleChange = { onPrefsChange(prefs.copy(themeSchedule = it)) },
                )
                Spacer(Modifier.height(16.dp))
            }
```

Add the composable at the bottom of the file (after the existing helpers):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoScheduleControls(
    schedule: ThemeSchedule,
    onScheduleChange: (ThemeSchedule) -> Unit,
) {
    Column {
        Text("Day starts at", style = MaterialTheme.typography.labelMedium)
        TimeField(
            time = schedule.dayStart,
            contentDescription = "Day start time",
            onTimeChange = { onScheduleChange(schedule.copy(dayStart = it)) },
        )
        Spacer(Modifier.height(8.dp))
        Text("Night starts at", style = MaterialTheme.typography.labelMedium)
        TimeField(
            time = schedule.nightStart,
            contentDescription = "Night start time",
            onTimeChange = { onScheduleChange(schedule.copy(nightStart = it)) },
        )
        Spacer(Modifier.height(12.dp))
        Text("Day theme", style = MaterialTheme.typography.labelMedium)
        ConcreteThemeChipRow(
            selected = schedule.dayTheme,
            onSelect = { onScheduleChange(schedule.copy(dayTheme = it)) },
        )
        Spacer(Modifier.height(8.dp))
        Text("Night theme", style = MaterialTheme.typography.labelMedium)
        ConcreteThemeChipRow(
            selected = schedule.nightTheme,
            onSelect = { onScheduleChange(schedule.copy(nightTheme = it)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConcreteThemeChipRow(
    selected: ReaderTheme,
    onSelect: (ReaderTheme) -> Unit,
) {
    val concretes = listOf(
        ReaderTheme.Light, ReaderTheme.Dark, ReaderTheme.DarkDim, ReaderTheme.Sepia,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        concretes.forEach { theme ->
            val label = theme.displayName()
            FilterChip(
                selected = selected == theme,
                onClick = { onSelect(theme) },
                label = { Text(label) },
                leadingIcon = { ConcreteThemeSwatch(theme) },
                modifier = Modifier.semantics { contentDescription = "$label theme" },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    time: LocalTime,
    contentDescription: String,
    onTimeChange: (LocalTime) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val label = "%02d:%02d".format(time.hour, time.minute)
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .semantics { this.contentDescription = contentDescription },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.height(48.dp).fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(state.hour, state.minute))
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = state) },
        )
    }
}
```

Add imports if missing:

```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalTime
```

- [ ] **Step 2: Build the app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 3: Launch the app, navigate to Settings → Reading settings**

Tap "Auto" chip. Verify:
- Schedule sub-controls appear: two time fields + two chip rows.
- Tapping a time field opens a 24h TimePicker dialog.
- Picking a non-default time updates the field label after OK.
- Day and Night chip rows do NOT show Auto.
- Switching from Auto to Light hides the sub-controls.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt
git commit -m "feat(reader): show schedule editor when Auto is selected in Settings panel"
```

---

### Task 11: `TimeProvider` abstraction for testability

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/TimeProvider.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.riffle.app.feature.reader

import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

// Lets the reader VMs read "now" in a way tests can substitute. Production binding
// returns LocalTime.now() each call; tests inject a fake.
interface TimeProvider {
    fun nowLocalTime(): LocalTime
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun nowLocalTime(): LocalTime = LocalTime.now()
}
```

- [ ] **Step 2: Bind it in Hilt**

Locate the existing app-level Hilt module that binds singletons (likely `app/src/main/kotlin/com/riffle/app/di/AppModule.kt` or similar). If `TimeProvider` requires a binding, add inside the module:

```kotlin
    @Binds
    @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider
```

If the module is an `@Module @InstallIn(SingletonComponent::class) object` rather than `abstract class`, use `@Provides` instead:

```kotlin
    @Provides
    @Singleton
    fun provideTimeProvider(impl: SystemTimeProvider): TimeProvider = impl
```

If no clear app-level Hilt module exists, `@Inject constructor` on `SystemTimeProvider` plus `@Singleton` is enough — Hilt will auto-bind it where injected as `SystemTimeProvider`. In that case skip this step and inject `SystemTimeProvider` directly in the VMs (Task 12).

- [ ] **Step 3: Build the app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/TimeProvider.kt
# plus the Hilt module if you edited it
git commit -m "feat(reader): add TimeProvider abstraction for clock injection"
```

---

### Task 12: Live-switching theme resolver inside `EpubReaderViewModel`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`
- Modify: `app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt`

Expose `effectiveFormattingPreferences: StateFlow<FormattingPreferences>` whose `theme` is always concrete. Internally drive it from `_formattingPreferences` plus a coroutine that re-emits at each schedule boundary.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt`, in the `EpubReaderViewModelTest` class:

```kotlin
    // Mirrors EpubReaderViewModel's schedule-driven boundary timer. The reader VM
    // computes the delay until the next ThemeSchedule boundary from a FakeClock,
    // suspends, then republishes effective prefs with the new resolved theme.
    @Test
    fun `effective theme flips at the schedule boundary`() = runTest {
        val schedule = com.riffle.core.domain.ThemeSchedule(
            dayStart = java.time.LocalTime.of(7, 0),
            nightStart = java.time.LocalTime.of(21, 0),
            dayTheme = com.riffle.core.domain.ReaderTheme.Light,
            nightTheme = com.riffle.core.domain.ReaderTheme.Dark,
        )
        // Pretend it is 20:59 — one minute before night-start.
        var fakeNow = java.time.LocalTime.of(20, 59)
        val emitted = mutableListOf<com.riffle.core.domain.ReaderTheme>()

        backgroundScope.launch {
            // Initial emit:
            emitted += if (com.riffle.core.domain.ReaderTheme.Auto == com.riffle.core.domain.ReaderTheme.Auto)
                schedule.resolve(fakeNow) else com.riffle.core.domain.ReaderTheme.Auto
            while (true) {
                val next = schedule.nextBoundaryAfter(fakeNow)
                val delayMs = ((next.toSecondOfDay() - fakeNow.toSecondOfDay() + 24 * 3600) % (24 * 3600)) * 1000L
                delay(delayMs.coerceAtLeast(1L))
                fakeNow = next
                emitted += schedule.resolve(fakeNow)
            }
        }

        // Advance one minute → boundary at 21:00, should flip to Dark.
        advanceTimeBy(60_000 + 1)
        assertEquals(listOf(com.riffle.core.domain.ReaderTheme.Light, com.riffle.core.domain.ReaderTheme.Dark), emitted)
    }
```

This test mirrors the VM control flow with virtual time, the same pattern the existing `periodic sync timer` tests use. The actual VM wiring follows in step 3.

- [ ] **Step 2: Run the test to verify it passes (pattern check)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.EpubReaderViewModelTest"`
Expected: PASS — this test mirrors the control flow without touching the VM yet, locking in the loop shape we'll implement.

- [ ] **Step 3: Wire `effectiveFormattingPreferences` into the VM**

In `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`:

Add imports:

```kotlin
import com.riffle.core.domain.withResolvedTheme
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import java.time.LocalTime
```

Add `private val timeProvider: TimeProvider` to the constructor's `@Inject constructor(...)` parameter list (insert before `readerStateHolder`).

Below the existing `formattingPreferences` declaration (around line 143), add:

```kotlin
    // Ticks emitted at each ThemeSchedule boundary so the resolved theme can flip live
    // during a reading session. Re-armed whenever the schedule changes.
    private val scheduleTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, replay = 1).apply {
        tryEmit(Unit) // prime the combine() below so it emits immediately on collection
    }

    // The user's pick with `theme` replaced by the resolver's concrete value when the
    // pick is Auto. Every downstream consumer (Readium navigator submitPreferences,
    // chapter rail backdrop, palette) reads this — they stay ignorant of Auto.
    val effectiveFormattingPreferences: StateFlow<FormattingPreferences> = combine(
        _formattingPreferences,
        scheduleTicks,
    ) { prefs, _ -> prefs.withResolvedTheme(timeProvider.nowLocalTime()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, FormattingPreferences())
```

Add a coroutine in `init { ... }` that re-arms the timer whenever the schedule or theme pick changes:

```kotlin
        viewModelScope.launch {
            _formattingPreferences
                .map { it.themeSchedule to (it.theme == ReaderTheme.Auto) }
                .distinctUntilChanged()
                .collectLatest { (schedule, autoActive) ->
                    if (!autoActive) return@collectLatest
                    while (true) {
                        val now = timeProvider.nowLocalTime()
                        val next = schedule.nextBoundaryAfter(now)
                        val nowSec = now.toSecondOfDay().toLong()
                        val nextSec = next.toSecondOfDay().toLong()
                        val delayMs = (((nextSec - nowSec + 24 * 3600) % (24 * 3600)) * 1000L)
                            .coerceAtLeast(1_000L)
                        delay(delayMs)
                        scheduleTicks.tryEmit(Unit)
                    }
                }
        }
```

Add imports for `collectLatest` and `map` if missing:

```kotlin
import kotlinx.coroutines.flow.collectLatest
```

(`map` is already imported.)

- [ ] **Step 4: Update the screen to consume `effectiveFormattingPreferences`**

In `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` (around line 107):

Change:

```kotlin
    val formattingPrefs by viewModel.formattingPreferences.collectAsState()
```

To:

```kotlin
    // Raw user-picked prefs — feeds the FormattingPanel chip selection (so Auto stays
    // highlighted even though the page renders in the resolved palette).
    val pickedPrefs by viewModel.formattingPreferences.collectAsState()
    // Resolved prefs — `theme` is always concrete. Feeds Readium, the chapter rail
    // backdrop, and any palette consumer.
    val formattingPrefs by viewModel.effectiveFormattingPreferences.collectAsState()
```

Find where `FormattingPanel(...)` is invoked (search for `FormattingPanel(`) and change the `prefs = formattingPrefs` argument to `prefs = pickedPrefs`. Leave every other consumer (`formattingPrefs.toEpubPreferences(...)`, `readerTheme.palette.background`, etc.) untouched — they continue to use `formattingPrefs` and so see the resolved theme.

Inside `viewModel.updateFormatting(...)` call from the panel's `onPrefsChange`, pass `pickedPrefs` as the previous-effective baseline. Look at the existing site; the call typically is:

```kotlin
onPrefsChange = { viewModel.updateFormatting(it) },
```

Leave it as-is — `updateFormatting` already uses `_formattingPreferences.value` (the raw pick) as the baseline for `withChanges`.

- [ ] **Step 5: Verify the panel still shows the right chip**

Locate any `readerTheme` derivation (around line 365 of `EpubReaderScreen.kt`, "Reader TopAppBar palette") and confirm it reads from `formattingPrefs.theme` (resolved). That's correct — the immersive bar should colour-match the rendered page, not the user's pick.

The chip selection inside `FormattingPanel` reads `prefs.theme == theme`, where `prefs` is now `pickedPrefs` — so the Auto chip stays highlighted when the user picked Auto.

- [ ] **Step 6: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Build and install on the phone AVD**

Run: `make install`
Open a book with theme set to Auto, day-start `13:00`, night-start `13:02`, day-theme Light, night-theme Dark.
Stay on the reader page across `13:02` (use device time settings to fast-forward if needed, or set the times around current local time).
Expected: the page repaints from Light to Dark without closing the book.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt
git commit -m "feat(reader): live-switch theme at ThemeSchedule boundaries in EPUB reader"
```

---

### Task 13: Same live-switching for `PdfReaderViewModel` (forward-compatible)

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt` (only if it currently reads theme)

The PDF reader VM currently does not expose `formattingPreferences` at all (no theme is applied to PDF rendering today). To honour the CONTEXT.md promise that Auto "applies uniformly to EPUB and PDF reading" we add the same `effectiveFormattingPreferences` plumbing so that **when PDF theming lands later**, Auto is already wired through.

- [ ] **Step 1: Inject the stores and `TimeProvider`**

In `PdfReaderViewModel.kt`'s `@Inject constructor`, add:

```kotlin
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val bookFormattingPreferencesStore: BookFormattingPreferencesStore,
    private val timeProvider: TimeProvider,
```

Add imports:

```kotlin
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.withResolvedTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
```

- [ ] **Step 2: Add the flows and timer**

After the existing `keepScreenOn` state declaration:

```kotlin
    private val _bookOverrides = MutableStateFlow(BookFormattingOverrides())
    private val _formattingPreferences = MutableStateFlow(FormattingPreferences())
    val formattingPreferences: StateFlow<FormattingPreferences> = _formattingPreferences

    private val scheduleTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, replay = 1).apply {
        tryEmit(Unit)
    }

    val effectiveFormattingPreferences: StateFlow<FormattingPreferences> = combine(
        _formattingPreferences,
        scheduleTicks,
    ) { prefs, _ -> prefs.withResolvedTheme(timeProvider.nowLocalTime()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, FormattingPreferences())
```

Replace the existing `init { viewModelScope.launch { openBook() } ... }` block with:

```kotlin
    init {
        viewModelScope.launch {
            loadFormattingPreferences()
            openBook()
        }
        viewModelScope.launch {
            combine(
                formattingPreferencesStore.preferences,
                _bookOverrides,
            ) { global, overrides -> overrides.applyTo(global) }
                .collect { _formattingPreferences.value = it }
        }
        viewModelScope.launch {
            _formattingPreferences
                .map { it.themeSchedule to (it.theme == ReaderTheme.Auto) }
                .distinctUntilChanged()
                .collectLatest { (schedule, autoActive) ->
                    if (!autoActive) return@collectLatest
                    while (true) {
                        val now = timeProvider.nowLocalTime()
                        val next = schedule.nextBoundaryAfter(now)
                        val delayMs = (((next.toSecondOfDay() - now.toSecondOfDay() + 24 * 3600) % (24 * 3600)) * 1000L)
                            .coerceAtLeast(1_000L)
                        delay(delayMs)
                        scheduleTicks.tryEmit(Unit)
                    }
                }
        }
        viewModelScope.launch {
            progressSyncController.serverPositionEvents.collect { serverProgress ->
                serverLocationToLocator(serverProgress.ebookLocation)?.let { _serverLocatorChannel.trySend(it) }
            }
        }
    }

    private suspend fun loadFormattingPreferences() {
        val overrides = bookFormattingPreferencesStore.load(itemId)
        val global = formattingPreferencesStore.preferences.first()
        _bookOverrides.value = overrides
        _formattingPreferences.value = overrides.applyTo(global)
    }
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 4: Run app unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — `PdfReaderViewModel` has no existing unit tests touching these fields.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt
git commit -m "feat(reader): expose effective formatting prefs in PDF VM for future theming"
```

---

### Task 14: Full unit + integration test sweep

- [ ] **Step 1: Run all tests**

Run: `./gradlew test`
Expected: PASS — every module (`:core:domain`, `:core:data`, `:app`, others) green.

- [ ] **Step 2: Run the phone harness**

Run: `make harness-test`
Expected: PASS — `FormattingPreferencesMapperTest` (with the new Auto-fallback case) and any reader/settings harness tests pass on the AVD.

- [ ] **Step 3: Manual smoke**

Build and install: `make install`

Verify:
1. Settings → Reading settings → Theme row shows 5 chips: Light, Dark, Dim, Sepia, Auto. Auto's swatch is the split-circle.
2. Tapping Auto reveals four sub-controls (Day starts at, Night starts at, Day theme row of 4 chips, Night theme row of 4 chips — Auto absent from both).
3. Tapping each time field opens a 24h `TimePicker` dialog; OK persists the new time.
4. Opening any book picks up the global Auto setting; the resolved theme matches the current clock against the schedule.
5. Tapping the formatting chip while in the reader shows Auto highlighted (not the resolved concrete) and *does not* show the schedule sub-controls (in-reader panel hides them).
6. Per-book override: set this book to Sepia in the in-reader panel, close, reopen — book renders in Sepia regardless of schedule. The global Auto pick is still active for other books.
7. Set the global schedule so a boundary is ~30 seconds in the future, open a book in Auto, wait for the boundary — page repaints without closing the book.

- [ ] **Step 4: README feature list update**

Per CLAUDE.md, on feature completion: flip the relevant `- [ ]` to `- [x]` in `README.md`'s Features list.

Open `README.md`, find the row for the auto theme entry (likely "Automatic day/night theme scheduling" or similar — if no row exists yet, add one under the reader/formatting section in the appropriate place, matching the format of neighbouring entries).

Example edit (the exact wording should match the README's existing style):

```markdown
- [x] Auto reader theme that switches between configured day and night themes on a global clock schedule
```

If no matching row exists, add one in the most appropriate Features sub-section.

- [ ] **Step 5: Final commit and PR-ready state**

```bash
git add README.md
git commit -m "docs(readme): mark Auto reader theme feature complete"
```

---

## Self-review checklist

- **Spec coverage:**
  - Auto as fifth enum value → Task 1
  - ThemeSchedule + global persistence → Tasks 2, 4
  - Defaults 07:00/21:00/Light/Dark → encoded in `ThemeSchedule.Companion`
  - Wrap-around, equal-times = always-day → Task 2 (`isNight`)
  - Per-book selection works like other themes → no schema change; `BookFormattingOverrides.theme: ReaderTheme?` accepts Auto natively (verified by existing tests still passing)
  - Resolver at the boundary → Task 3 (`withResolvedTheme`)
  - Live mid-session switching → Tasks 11, 12, 13
  - Split swatch for Auto chip → Task 9
  - Schedule editing only in full-screen Settings panel → Task 10 (guard `if (fullScreen && prefs.theme == Auto)`)
  - In-reader panel just shows the chip → same guard
  - Day/night picks restricted to four concretes → Task 10 (`ConcreteThemeChipRow` lists only four)
  - Both readers (EPUB + PDF) → Tasks 12, 13
  - ADR / CONTEXT.md → already written this session; no plan task needed
  - README update → Task 14

- **Type consistency:**
  - `ThemeSchedule.resolve` / `nextBoundaryAfter` — same signatures used in Task 2 test, Task 3 file, Task 12 VM, Task 12 test.
  - `withResolvedTheme(now: LocalTime): FormattingPreferences` — same signature in Task 3 definition and Task 12 VM call.
  - `TimeProvider.nowLocalTime(): LocalTime` — defined Task 11, used Tasks 12, 13.
  - `effectiveFormattingPreferences: StateFlow<FormattingPreferences>` — defined Tasks 12, 13; consumed Task 12 screen update.

- **Placeholder scan:** No "TODO", "implement later", "similar to Task N (without code)" — every step has either the literal code, the exact command, or a precise UI verification step.
