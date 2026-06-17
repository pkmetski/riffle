# Reader Settings Reorganization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the monolithic reader `FormattingPanel` into three reusable section composables (Formatting / Display / Behavior) consumed by two thin hosts — a tabbed in-reader sheet and three drill-in Settings panels — so each setting is defined once and appears on both surfaces.

**Architecture:** One taxonomy, two presentations. Three stateless section composables own all controls; the reader host shows them as tabs, the Settings host shows each as a full-screen drill-in. Host-specific divergence (the Auto schedule editor) is gated by a `scheduleEditable` flag, not a fork. The reader's "Reset to global defaults" button becomes reader-host-only chrome, removing the permanently-disabled copy that exists in Settings today.

**Tech Stack:** Kotlin, Jetbrains Compose / Material3, Hilt, JUnit4 (JVM unit tests), AndroidX Compose UI test + `createAndroidComposeRule` (instrumented, run via `make harness-test`).

## Global Constraints

- `JAVA_HOME` must be set for Gradle: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Run the full JVM suite with `./gradlew test` (module-specific `:testDebugUnitTest` misses pure-JVM modules); CI uses `./gradlew test`.
- Instrumented (androidTest) tests run ONLY via `make harness-test` (phone) — never `./gradlew :app:connectedDebugAndroidTest` directly.
- No changes to preference stores, DataStore keys, Room schema, or per-device scoping. Pure UI/composition reorganization.
- No new settings. The only behavior changes: (1) Settings no longer shows the dead "Reset to global defaults" button; (2) the reader shows a read-only Auto-schedule summary instead of the editor.
- New composables live in package `com.riffle.app.feature.reader` (sections, sheet, shared controls, summaries) and `com.riffle.app.feature.settings` (the three drill-in detail panels), following existing package layout.
- Shared helpers extracted from `FormattingPanel.kt` change visibility `private` → `internal` (cross-file, same module).

---

## File Structure

**Create:**
- `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsSummaries.kt` — pure (non-Compose) label + summary functions. JVM-testable.
- `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsControls.kt` — shared `internal` Compose helpers moved out of `FormattingPanel.kt`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingSection.kt` — `FormattingSection` composable.
- `app/src/main/kotlin/com/riffle/app/feature/reader/DisplaySection.kt` — `DisplaySection` composable.
- `app/src/main/kotlin/com/riffle/app/feature/reader/BehaviorSection.kt` — `BehaviorSection` composable.
- `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsSheet.kt` — tabbed in-reader host.
- `app/src/main/kotlin/com/riffle/app/feature/settings/ReaderSettingsDetailPanels.kt` — `FormattingSettingsPanel`, `DisplaySettingsPanel`, `BehaviorSettingsPanel` + private `DetailScaffold`.
- `app/src/test/kotlin/com/riffle/app/feature/reader/ReaderSettingsSummariesTest.kt` — JVM unit tests.
- `app/src/androidTest/kotlin/com/riffle/app/feature/reader/ReaderSettingsSectionsTest.kt` — instrumented Compose tests.

**Modify:**
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt:528-541` — swap `FormattingPanel(...)` for `ReaderSettingsSheet(...)`.
- `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt` — Reading group of three rows + three overlays; drop the dead Formatting overlay.

**Delete:**
- `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt` — fully replaced (Task 8).

---

## Task 1: Pure label & summary functions (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsSummaries.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/ReaderSettingsSummariesTest.kt`

**Interfaces:**
- Produces (all top-level in package `com.riffle.app.feature.reader`):
  - `fun ReaderTheme.label(): String`
  - `fun ReaderFontFamily.label(): String`
  - `fun lineSpacingWord(value: Float): String`
  - `fun marginsWord(value: Float): String`
  - `fun formattingSummary(prefs: FormattingPreferences): String`
  - `fun displaySummary(prefs: FormattingPreferences): String`
  - `fun behaviorSummary(keepScreenOn: Boolean, volumeKeyNavigationEnabled: Boolean): String`
  - `fun autoScheduleSummary(schedule: ThemeSchedule): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class ReaderSettingsSummariesTest {

    private val defaults = FormattingPreferences()

    @Test fun themeLabels() {
        assertEquals("Light", ReaderTheme.Light.label())
        assertEquals("Dim", ReaderTheme.DarkDim.label())
        assertEquals("Auto", ReaderTheme.Auto.label())
    }

    @Test fun fontLabels() {
        assertEquals("Serif", ReaderFontFamily.Serif.label())
        assertEquals("Sans serif", ReaderFontFamily.SansSerif.label())
        assertEquals("Dyslexic", ReaderFontFamily.OpenDyslexic.label())
    }

    @Test fun lineSpacingWords() {
        assertEquals("Normal", lineSpacingWord(1.5f))
        assertEquals("Tight", lineSpacingWord(1.0f))
    }

    @Test fun marginsWords() {
        assertEquals("Normal", marginsWord(1.0f))
        assertEquals("Wide", marginsWord(3.0f))
    }

    @Test fun formattingSummaryShowsFontSizeAndMargins() {
        val prefs = defaults.copy(
            fontFamily = ReaderFontFamily.Serif,
            fontSize = 1.1f,
            margins = 1.0f,
        )
        assertEquals("Serif · 110% · Normal margins", formattingSummary(prefs))
    }

    @Test fun displaySummaryShowsThemeModeAndChapterMap() {
        val prefs = defaults.copy(
            theme = ReaderTheme.Light,
            orientation = ReaderOrientation.Horizontal,
            showChapterMap = true,
        )
        assertEquals("Light · Paginated · map on", displaySummary(prefs))
    }

    @Test fun displaySummaryScrollAndMapOff() {
        val prefs = defaults.copy(
            theme = ReaderTheme.Sepia,
            orientation = ReaderOrientation.Vertical,
            showChapterMap = false,
        )
        assertEquals("Sepia · Scroll · map off", displaySummary(prefs))
    }

    @Test fun behaviorSummary() {
        assertEquals("Keep screen on · volume nav off", behaviorSummary(keepScreenOn = true, volumeKeyNavigationEnabled = false))
        assertEquals("Keep screen off · volume nav on", behaviorSummary(keepScreenOn = false, volumeKeyNavigationEnabled = true))
    }

    @Test fun autoScheduleSummaryFormatsTimesAndThemes() {
        val schedule = ThemeSchedule(
            dayStart = LocalTime.of(7, 0),
            nightStart = LocalTime.of(21, 0),
            dayTheme = ReaderTheme.Light,
            nightTheme = ReaderTheme.Dark,
        )
        assertEquals("Day 07:00 · Light → Night 21:00 · Dark", autoScheduleSummary(schedule))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.ReaderSettingsSummariesTest"`
Expected: FAIL — unresolved references (`label`, `formattingSummary`, …).

> Before writing Step 3, open `core/domain/.../FormattingPreferences.kt` and confirm the `ThemeSchedule` constructor parameter names (`dayStart`, `nightStart`, `dayTheme`, `nightTheme`) and `FormattingPreferences` defaults match the test. If they differ, fix the test to match the real type, then continue.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import kotlin.math.roundToInt

fun ReaderTheme.label(): String = when (this) {
    ReaderTheme.Light -> "Light"
    ReaderTheme.Dark -> "Dark"
    ReaderTheme.DarkDim -> "Dim"
    ReaderTheme.Sepia -> "Sepia"
    ReaderTheme.Auto -> "Auto"
}

fun ReaderFontFamily.label(): String = when (this) {
    ReaderFontFamily.Serif -> "Serif"
    ReaderFontFamily.SansSerif -> "Sans serif"
    ReaderFontFamily.Monospace -> "Mono"
    ReaderFontFamily.Literata -> "Literata"
    ReaderFontFamily.Merriweather -> "Merriweather"
    ReaderFontFamily.OpenDyslexic -> "Dyslexic"
}

fun lineSpacingWord(value: Float): String = when {
    value < 1.15f -> "Tight"
    value < 1.35f -> "Compact"
    value < 1.55f -> "Normal"
    value < 1.75f -> "Comfortable"
    value < 1.95f -> "Roomy"
    else -> "Spacious"
}

fun marginsWord(value: Float): String = when {
    value < 0.5f -> "Edge"
    value < 0.85f -> "Tight"
    value < 1.25f -> "Normal"
    value < 1.75f -> "Comfortable"
    value < 2.35f -> "Roomy"
    else -> "Wide"
}

fun formattingSummary(prefs: FormattingPreferences): String =
    "${prefs.fontFamily.label()} · ${(prefs.fontSize * 100).roundToInt()}% · ${marginsWord(prefs.margins)} margins"

fun displaySummary(prefs: FormattingPreferences): String {
    val mode = if (prefs.orientation == ReaderOrientation.Horizontal) "Paginated" else "Scroll"
    val map = if (prefs.showChapterMap) "map on" else "map off"
    return "${prefs.theme.label()} · $mode · $map"
}

fun behaviorSummary(keepScreenOn: Boolean, volumeKeyNavigationEnabled: Boolean): String =
    "Keep screen ${if (keepScreenOn) "on" else "off"} · volume nav ${if (volumeKeyNavigationEnabled) "on" else "off"}"

fun autoScheduleSummary(schedule: ThemeSchedule): String {
    fun t(time: java.time.LocalTime) = "%02d:%02d".format(time.hour, time.minute)
    return "Day ${t(schedule.dayStart)} · ${schedule.dayTheme.label()} → " +
        "Night ${t(schedule.nightStart)} · ${schedule.nightTheme.label()}"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.ReaderSettingsSummariesTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsSummaries.kt app/src/test/kotlin/com/riffle/app/feature/reader/ReaderSettingsSummariesTest.kt
git commit -m "feat(reader-settings): pure label and summary helpers"
```

---

## Task 2: Extract shared Compose controls

Move the reusable private helpers out of `FormattingPanel.kt` into a shared file so all three sections + hosts use one copy. `FormattingPanel.kt` keeps compiling against them until it is deleted in Task 8.

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsControls.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt` (remove the moved declarations)

**Interfaces:**
- Consumes: `ReaderTheme.label()`, `ReaderFontFamily.label()`, `lineSpacingWord`, `marginsWord` from Task 1.
- Produces (all `internal` in package `com.riffle.app.feature.reader`):
  - `@Composable fun StepperRow(label, onDecrement, onIncrement, decrementDescription, incrementDescription)`
  - `@Composable fun SectionDivider()`
  - `@Composable fun FontChipRow(fonts: List<ReaderFontFamily>, selected: ReaderFontFamily, onSelect: (ReaderFontFamily) -> Unit)`
  - `@Composable fun ThemeSwatch(theme: ReaderTheme, schedule: ThemeSchedule)`
  - `@Composable fun OrientationIcon(orientation: ReaderOrientation)`
  - `@Composable fun AutoScheduleControls(schedule: ThemeSchedule, onScheduleChange: (ThemeSchedule) -> Unit)`
  - `@Composable fun AutoScheduleSummaryCard(schedule: ThemeSchedule)` (NEW — read-only summary for the reader)
  - `internal fun Float.round1(): Float`

- [ ] **Step 1: Create `ReaderSettingsControls.kt`**

Move these declarations VERBATIM from the current `FormattingPanel.kt` into the new file, changing each `private` to `internal`, and delete `ReaderTheme.displayName` / `ReaderFontFamily.displayName` in favor of Task 1's `label()`:

- `StepperRow` (`FormattingPanel.kt:477-511`)
- `SectionDivider` (`:516-524`)
- `ThemeSwatch`, `ConcreteThemeSwatch`, `AutoThemeSwatch` (`:534-584`) — keep `ConcreteThemeSwatch`/`AutoThemeSwatch` `private` to this file.
- `OrientationIcon` (`:586-616`)
- `FontChipRow` (`:636-659`) — change its label call from `family.displayName()` to `family.label()`.
- `previewFontFamily`, `rememberAssetFontFamily` (`:661-689`) — keep `private`.
- `round1` (`:700`) → `internal fun Float.round1()`.
- `AutoScheduleControls`, `ConcreteThemeDropdown`, `TimeField` (`:702-831`) — change `selected.displayName()` calls in `ConcreteThemeDropdown` to `selected.label()` / `theme.label()`.

Add the file header and imports needed by the moved code (copy the relevant imports from `FormattingPanel.kt:1-74`). Mark the file `@file:OptIn(ExperimentalMaterial3Api::class)` since `ConcreteThemeDropdown`/`TimeField` use it.

Then add the NEW read-only summary card (used by the reader's Display section):

```kotlin
@Composable
internal fun AutoScheduleSummaryCard(schedule: ThemeSchedule) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(autoScheduleSummary(schedule), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Edit the schedule in Settings → Display",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 2: Remove the moved declarations from `FormattingPanel.kt`**

Delete from `FormattingPanel.kt` every declaration listed in Step 1 (lines `477-831`, except the top-level `FormattingPanel` composable itself which ends at line 475). Also delete the now-unused `displayName` extensions. Leave `FormattingPanel` (`:76-475`) intact — it now calls the `internal` helpers in the new file (same package, no import needed). Remove any imports in `FormattingPanel.kt` that are now unused (e.g. `Canvas`, `TimePicker`, `rememberTimePickerState`, `ExposedDropdownMenuBox`) — the Kotlin compiler warning list after Step 3 tells you which.

- [ ] **Step 3: Verify it compiles**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (warnings about unused imports are acceptable; remove them if present).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsControls.kt app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt
git commit -m "refactor(reader-settings): extract shared formatting controls"
```

---

## Task 3: FormattingSection composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingSection.kt`

**Interfaces:**
- Consumes: `StepperRow`, `FontChipRow`, `round1` (Task 2).
- Produces: `@Composable fun FormattingSection(prefs: FormattingPreferences, onPrefsChange: (FormattingPreferences) -> Unit)`

- [ ] **Step 1: Write the composable**

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily

@Composable
fun FormattingSection(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
) {
    Column {
        Text("Text", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        Text("Font size", style = MaterialTheme.typography.labelMedium)
        StepperRow(
            label = "%.0f%%".format(prefs.fontSize * 100),
            onDecrement = { onPrefsChange(prefs.copy(fontSize = (prefs.fontSize - 0.1f).coerceAtLeast(0.5f).round1())) },
            onIncrement = { onPrefsChange(prefs.copy(fontSize = (prefs.fontSize + 0.1f).coerceAtMost(2.5f).round1())) },
            decrementDescription = "Decrease font size",
            incrementDescription = "Increase font size",
        )
        Spacer(Modifier.height(16.dp))

        Text("Font", style = MaterialTheme.typography.labelMedium)
        val genericFonts = listOf(ReaderFontFamily.Serif, ReaderFontFamily.SansSerif, ReaderFontFamily.Monospace)
        val bundledFonts = listOf(ReaderFontFamily.Literata, ReaderFontFamily.Merriweather, ReaderFontFamily.OpenDyslexic)
        FontChipRow(genericFonts, prefs.fontFamily) { onPrefsChange(prefs.copy(fontFamily = it)) }
        Spacer(Modifier.height(4.dp))
        FontChipRow(bundledFonts, prefs.fontFamily) { onPrefsChange(prefs.copy(fontFamily = it)) }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Justify text", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = prefs.justifyText, onCheckedChange = { onPrefsChange(prefs.copy(justifyText = it)) })
        }
        Spacer(Modifier.height(16.dp))

        Text("Line spacing", style = MaterialTheme.typography.labelMedium)
        StepperRow(
            label = "${lineSpacingWord(prefs.lineSpacing)} · %.1f×".format(prefs.lineSpacing),
            onDecrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing - 0.1f).coerceAtLeast(1.0f).round1())) },
            onIncrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing + 0.1f).coerceAtMost(2.0f).round1())) },
            decrementDescription = "Decrease line spacing",
            incrementDescription = "Increase line spacing",
        )
        Spacer(Modifier.height(20.dp))

        Text("Page", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Text("Margins", style = MaterialTheme.typography.labelMedium)
        StepperRow(
            label = "${marginsWord(prefs.margins)} · %.1f×".format(prefs.margins),
            onDecrement = { onPrefsChange(prefs.copy(margins = (prefs.margins - 0.1f).coerceAtLeast(0.2f).round1())) },
            onIncrement = { onPrefsChange(prefs.copy(margins = (prefs.margins + 0.1f).coerceAtMost(3.0f).round1())) },
            decrementDescription = "Decrease margins",
            incrementDescription = "Increase margins",
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/FormattingSection.kt
git commit -m "feat(reader-settings): FormattingSection composable"
```

---

## Task 4: DisplaySection composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/DisplaySection.kt`

**Interfaces:**
- Consumes: `ThemeSwatch`, `OrientationIcon`, `AutoScheduleControls`, `AutoScheduleSummaryCard` (Task 2).
- Produces: `@Composable fun DisplaySection(prefs: FormattingPreferences, onPrefsChange: (FormattingPreferences) -> Unit, scheduleEditable: Boolean)`

- [ ] **Step 1: Write the composable**

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySection(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    scheduleEditable: Boolean,
) {
    Column {
        // Theme
        Text("Theme", style = MaterialTheme.typography.labelMedium)
        val concreteThemes = listOf(ReaderTheme.Light, ReaderTheme.Dark, ReaderTheme.DarkDim, ReaderTheme.Sepia)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            concreteThemes.forEach { theme ->
                FilterChip(
                    selected = prefs.theme == theme,
                    onClick = { onPrefsChange(prefs.copy(theme = theme)) },
                    label = { Text(theme.label()) },
                    leadingIcon = { ThemeSwatch(theme, prefs.themeSchedule) },
                    modifier = Modifier.semantics { contentDescription = "${theme.label()} theme" },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = prefs.theme == ReaderTheme.Auto,
                onClick = { onPrefsChange(prefs.copy(theme = ReaderTheme.Auto)) },
                label = { Text(ReaderTheme.Auto.label()) },
                leadingIcon = { ThemeSwatch(ReaderTheme.Auto, prefs.themeSchedule) },
                modifier = Modifier.semantics { contentDescription = "${ReaderTheme.Auto.label()} theme" },
            )
        }
        if (prefs.theme == ReaderTheme.Auto) {
            Spacer(Modifier.height(12.dp))
            if (scheduleEditable) {
                AutoScheduleControls(
                    schedule = prefs.themeSchedule,
                    onScheduleChange = { onPrefsChange(prefs.copy(themeSchedule = it)) },
                )
            } else {
                AutoScheduleSummaryCard(prefs.themeSchedule)
            }
        }
        Spacer(Modifier.height(20.dp))

        // View
        Text("View", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Text("Reading mode", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderOrientation.entries.forEach { orientation ->
                val label = if (orientation == ReaderOrientation.Horizontal) "Paginated" else "Scroll"
                FilterChip(
                    selected = prefs.orientation == orientation,
                    onClick = { onPrefsChange(prefs.copy(orientation = orientation)) },
                    label = { Text(label) },
                    leadingIcon = { OrientationIcon(orientation) },
                    modifier = Modifier.semantics { contentDescription = "$label reading orientation" },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val doublePageEnabled = prefs.orientation == ReaderOrientation.Horizontal
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().alpha(if (doublePageEnabled) 1f else 0.38f),
        ) {
            Text("Double page in landscape", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = prefs.doublePageSpread,
                onCheckedChange = { onPrefsChange(prefs.copy(doublePageSpread = it)) },
                enabled = doublePageEnabled,
            )
        }
        Spacer(Modifier.height(20.dp))

        // On-screen info
        Text("On-screen info", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        ToggleRow("Chapter map", prefs.showChapterMap) { onPrefsChange(prefs.copy(showChapterMap = it)) }
        ToggleRow("Current chapter label", prefs.showCurrentChapterLabel) { onPrefsChange(prefs.copy(showCurrentChapterLabel = it)) }
        ToggleRow("Reading progress labels", prefs.showReadingProgressLabels) { onPrefsChange(prefs.copy(showReadingProgressLabels = it)) }
        ToggleRow("Time remaining", prefs.showReadingTimeEstimate) { onPrefsChange(prefs.copy(showReadingTimeEstimate = it)) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/DisplaySection.kt
git commit -m "feat(reader-settings): DisplaySection composable with scheduleEditable flag"
```

---

## Task 5: BehaviorSection composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/BehaviorSection.kt`

**Interfaces:**
- Produces: `@Composable fun BehaviorSection(keepScreenOn: Boolean, onKeepScreenOnChange: (Boolean) -> Unit, volumeKeyNavigationEnabled: Boolean, onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit, invertVolumeKeys: Boolean, onInvertVolumeKeysChange: (Boolean) -> Unit)`

- [ ] **Step 1: Write the composable** (control rows copied verbatim from `FormattingPanel.kt:402-468`)

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun BehaviorSection(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().toggleable(
                value = keepScreenOn, role = Role.Switch, onValueChange = onKeepScreenOnChange,
            ),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
                Text("Applies to all books", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = keepScreenOn, onCheckedChange = null)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().toggleable(
                value = volumeKeyNavigationEnabled, role = Role.Switch, onValueChange = onVolumeKeyNavigationEnabledChange,
            ),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Volume key navigation", style = MaterialTheme.typography.bodyLarge)
                Text("Applies to all books", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = volumeKeyNavigationEnabled, onCheckedChange = null)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = volumeKeyNavigationEnabled) { onInvertVolumeKeysChange(!invertVolumeKeys) }
                .alpha(if (volumeKeyNavigationEnabled) 1f else 0.38f),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Invert volume keys", style = MaterialTheme.typography.bodyLarge)
                Text("Applies to all books", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = invertVolumeKeys, onCheckedChange = onInvertVolumeKeysChange, enabled = volumeKeyNavigationEnabled)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/BehaviorSection.kt
git commit -m "feat(reader-settings): BehaviorSection composable"
```

---

## Task 6: Tabbed reader host + wire EpubReaderScreen

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsSheet.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt:528-541`

**Interfaces:**
- Consumes: `FormattingSection`, `DisplaySection`, `BehaviorSection`.
- Produces: `@Composable fun ReaderSettingsSheet(prefs, hasBookOverrides, onPrefsChange, onReset, onDismiss, keepScreenOn, onKeepScreenOnChange, volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange, invertVolumeKeys, onInvertVolumeKeysChange)` — same parameter list as the old `FormattingPanel` minus `fullScreen`.

- [ ] **Step 1: Write `ReaderSettingsSheet.kt`**

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.FormattingPreferences

@Composable
fun ReaderSettingsSheet(
    prefs: FormattingPreferences,
    hasBookOverrides: Boolean,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
) {
    val tabs = listOf("Formatting", "Display", "Behavior")
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Tap-catcher above the sheet dismisses; reader pane stays visible to preview changes.
        Box(
            modifier = Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 1.dp,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    when (selectedTab) {
                        0 -> FormattingSection(prefs, onPrefsChange)
                        1 -> DisplaySection(prefs, onPrefsChange, scheduleEditable = false)
                        else -> BehaviorSection(
                            keepScreenOn, onKeepScreenOnChange,
                            volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange,
                            invertVolumeKeys, onInvertVolumeKeysChange,
                        )
                    }
                }
                HorizontalDivider()
                TextButton(
                    onClick = onReset,
                    enabled = hasBookOverrides,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 4.dp)
                        .navigationBarsPadding(),
                ) {
                    Text("Reset to global defaults")
                }
            }
        }
    }
}
```

- [ ] **Step 2: Wire `EpubReaderScreen.kt`**

Replace the call at `EpubReaderScreen.kt:529-541` — change `FormattingPanel(` to `ReaderSettingsSheet(` and remove the `fullScreen` argument (there is none on that call). The full replacement block:

```kotlin
            ReaderSettingsSheet(
                prefs = pickedPrefs,
                hasBookOverrides = hasBookOverrides,
                onPrefsChange = { viewModel.updateFormatting(it) },
                onReset = { viewModel.resetToGlobalDefaults() },
                onDismiss = { showFormattingPanel = false },
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
                volumeKeyNavigationEnabled = volumeKeyNavigationEnabled,
                onVolumeKeyNavigationEnabledChange = { viewModel.setVolumeKeyNavigationEnabled(it) },
                invertVolumeKeys = invertVolumeKeys,
                onInvertVolumeKeysChange = { viewModel.setInvertVolumeKeys(it) },
            )
```

(`ReaderSettingsSheet` is in the same package as `EpubReaderScreen`, so no import change is needed.)

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsSheet.kt app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader-settings): tabbed reader settings sheet"
```

---

## Task 7: Settings drill-in panels + wire SettingsScreen

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/settings/ReaderSettingsDetailPanels.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `FormattingSection`, `DisplaySection`, `BehaviorSection`, `formattingSummary`, `displaySummary`, `behaviorSummary` (all package `com.riffle.app.feature.reader`).
- Produces:
  - `@Composable fun FormattingSettingsPanel(prefs, onPrefsChange, onDismiss)`
  - `@Composable fun DisplaySettingsPanel(prefs, onPrefsChange, onDismiss)`
  - `@Composable fun BehaviorSettingsPanel(keepScreenOn, onKeepScreenOnChange, volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange, invertVolumeKeys, onInvertVolumeKeysChange, onDismiss)`

- [ ] **Step 1: Write `ReaderSettingsDetailPanels.kt`** (DetailScaffold mirrors `ListeningPreferencesPanel`)

```kotlin
package com.riffle.app.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.BehaviorSection
import com.riffle.app.feature.reader.DisplaySection
import com.riffle.app.feature.reader.FormattingSection
import com.riffle.core.domain.FormattingPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    Surface(modifier = Modifier.fillMaxSize().statusBarsPadding(), tonalElevation = 1.dp) {
        Column {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
            ) { content() }
        }
    }
}

@Composable
fun FormattingSettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Formatting", onDismiss) { FormattingSection(prefs, onPrefsChange) }

@Composable
fun DisplaySettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Display", onDismiss) { DisplaySection(prefs, onPrefsChange, scheduleEditable = true) }

@Composable
fun BehaviorSettingsPanel(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Behavior", onDismiss) {
    BehaviorSection(
        keepScreenOn, onKeepScreenOnChange,
        volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange,
        invertVolumeKeys, onInvertVolumeKeysChange,
    )
}
```

- [ ] **Step 2: Update `SettingsScreen.kt` imports**

- Remove: `import com.riffle.app.feature.reader.FormattingPanel` (`:66`).
- Add: `import com.riffle.app.feature.reader.behaviorSummary`, `import com.riffle.app.feature.reader.displaySummary`, `import com.riffle.app.feature.reader.formattingSummary`.

- [ ] **Step 3: Replace the state booleans** (`SettingsScreen.kt:105`)

Replace:
```kotlin
    var showFormattingPanel by remember { mutableStateOf(false) }
```
with:
```kotlin
    var showFormattingPanel by remember { mutableStateOf(false) }
    var showDisplayPanel by remember { mutableStateOf(false) }
    var showBehaviorPanel by remember { mutableStateOf(false) }
```

- [ ] **Step 4: Replace the Reading group** (`SettingsScreen.kt:232-245`)

Replace the "Reading" header + single Formatting `ListItem` (lines 232-245) with:
```kotlin
                Text(
                    text = "Reading",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable { showFormattingPanel = true },
                    headlineContent = { Text("Formatting") },
                    supportingContent = { Text(formattingSummary(globalFormatting)) },
                    trailingContent = {
                        TextButton(onClick = { showFormattingPanel = true }) { Text("Edit") }
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { showDisplayPanel = true },
                    headlineContent = { Text("Display") },
                    supportingContent = { Text(displaySummary(globalFormatting)) },
                    trailingContent = {
                        TextButton(onClick = { showDisplayPanel = true }) { Text("Edit") }
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { showBehaviorPanel = true },
                    headlineContent = { Text("Behavior") },
                    supportingContent = { Text(behaviorSummary(keepScreenOn, volumeKeyNavigationEnabled)) },
                    trailingContent = {
                        TextButton(onClick = { showBehaviorPanel = true }) { Text("Edit") }
                    },
                )
```

- [ ] **Step 5: Replace the overlay** (`SettingsScreen.kt:377-392`)

Replace the `if (showFormattingPanel) { FormattingPanel(...) }` block with three overlays (note: the dead `hasBookOverrides = false` / `onReset = {}` are gone — Reset no longer exists in Settings):
```kotlin
    if (showFormattingPanel) {
        FormattingSettingsPanel(
            prefs = globalFormatting,
            onPrefsChange = { viewModel.updateGlobalFormatting(it) },
            onDismiss = { showFormattingPanel = false },
        )
    }

    if (showDisplayPanel) {
        DisplaySettingsPanel(
            prefs = globalFormatting,
            onPrefsChange = { viewModel.updateGlobalFormatting(it) },
            onDismiss = { showDisplayPanel = false },
        )
    }

    if (showBehaviorPanel) {
        BehaviorSettingsPanel(
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
            volumeKeyNavigationEnabled = volumeKeyNavigationEnabled,
            onVolumeKeyNavigationEnabledChange = { viewModel.setVolumeKeyNavigationEnabled(it) },
            invertVolumeKeys = invertVolumeKeys,
            onInvertVolumeKeysChange = { viewModel.setInvertVolumeKeys(it) },
            onDismiss = { showBehaviorPanel = false },
        )
    }
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/settings/ReaderSettingsDetailPanels.kt app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt
git commit -m "feat(reader-settings): three drill-in Settings panels; drop dead Reset button"
```

---

## Task 8: Delete the old FormattingPanel + full build & JVM tests

**Files:**
- Delete: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt`

- [ ] **Step 1: Confirm there are no remaining references**

Run: `grep -rn "FormattingPanel" app/src` 
Expected: no matches (both call sites migrated in Tasks 6 & 7).

- [ ] **Step 2: Delete the file**

```bash
git rm app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt
```

- [ ] **Step 3: Full app build**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full JVM test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (includes `ReaderSettingsSummariesTest`). If any pre-existing flaky `core:database` emission tests fail, re-run that module; they are unrelated to this change.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(reader-settings): remove monolithic FormattingPanel"
```

---

## Task 9: Instrumented Compose tests (run via harness)

**Files:**
- Create: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/ReaderSettingsSectionsTest.kt`

**Interfaces:**
- Consumes: `DisplaySection`, `ReaderSettingsSheet`.

- [ ] **Step 1: Write the instrumented tests**

```kotlin
package com.riffle.app.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSettingsSectionsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun readerSheet_showsAllThreeTabs_andBehaviorReachable() {
        composeTestRule.setContent {
            ReaderSettingsSheet(
                prefs = FormattingPreferences(),
                hasBookOverrides = false,
                onPrefsChange = {},
                onReset = {},
                onDismiss = {},
                keepScreenOn = false, onKeepScreenOnChange = {},
                volumeKeyNavigationEnabled = false, onVolumeKeyNavigationEnabledChange = {},
                invertVolumeKeys = false, onInvertVolumeKeysChange = {},
            )
        }
        composeTestRule.onNodeWithText("Formatting").assertIsDisplayed()
        composeTestRule.onNodeWithText("Display").assertIsDisplayed()
        composeTestRule.onNodeWithText("Behavior").assertIsDisplayed()
        // Behavior is reachable while reading.
        composeTestRule.onNodeWithText("Behavior").performClick()
        composeTestRule.onNodeWithText("Keep screen on").assertIsDisplayed()
    }

    @Test
    fun displaySection_editableHostShowsScheduleEditor() {
        composeTestRule.setContent {
            DisplaySection(
                prefs = FormattingPreferences().copy(theme = ReaderTheme.Auto),
                onPrefsChange = {},
                scheduleEditable = true,
            )
        }
        composeTestRule.onNodeWithText("Day starts at").assertIsDisplayed()
    }

    @Test
    fun displaySection_readerHostShowsReadOnlySummaryNotEditor() {
        composeTestRule.setContent {
            DisplaySection(
                prefs = FormattingPreferences().copy(theme = ReaderTheme.Auto),
                onPrefsChange = {},
                scheduleEditable = false,
            )
        }
        composeTestRule.onNodeWithText("Edit the schedule in Settings → Display").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Day starts at").assertCountEquals(0)
    }
}
```

> Add the imports the test references: `androidx.compose.ui.test.performClick`, `androidx.compose.ui.test.onAllNodesWithText`, `androidx.compose.ui.test.assertCountEquals`.

- [ ] **Step 2: Run via the harness**

Run: `make harness-test`
Expected: the three `ReaderSettingsSectionsTest` tests PASS. If `make harness-test` hangs on AVD naming, fall back to the pinned-serial recipe in the project memory (boot a Harness phone AVD, run `:app:connectedDebugAndroidTest` with `ANDROID_SERIAL` set and the class filter).

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/riffle/app/feature/reader/ReaderSettingsSectionsTest.kt
git commit -m "test(reader-settings): instrumented tabs + scheduleEditable coverage"
```

---

## Self-Review

**Spec coverage:**
- Three categories Formatting/Display/Behavior → Tasks 3, 4, 5. ✓
- Reader keeps everything as tabs → Task 6 (`ReaderSettingsSheet`). ✓
- Settings drill-in screens with summaries → Task 7. ✓
- Single source of truth (sections reused by both hosts) → Tasks 3-7. ✓
- Rename Appearance→Formatting (no app-chrome collision) → Task 7 row labels ("Reading" group, "Formatting" row), distinct from existing top-level "Appearance" section. ✓
- Theme + Reading mode + double-page in Display → Task 4. ✓
- Auto schedule allows any theme for day/night → unchanged `ConcreteThemeDropdown` (already all four concretes) reused via `AutoScheduleControls`. ✓
- `scheduleEditable` flag + reader read-only summary → Tasks 2 (`AutoScheduleSummaryCard`), 4. ✓
- Reset button reader-only; removed from Settings → Tasks 6 (sheet footer) & 7 (no Reset in panels). ✓
- "Aa" entry point unchanged → already present at `EpubReaderScreen.kt:501-506`; Task 6 only swaps the composable it opens. ✓

**Placeholder scan:** No TBD/TODO; all new logic shown in full; extraction steps give exact source line ranges. ✓

**Type consistency:** `FormattingPreferences` / `ThemeSchedule` field names (`fontSize`, `fontFamily`, `margins`, `orientation`, `theme`, `themeSchedule`, `showChapterMap`, `dayStart`, `nightStart`, `dayTheme`, `nightTheme`) used identically across summaries, sections, and tests, matching `core/domain/FormattingPreferences.kt`. Section composable signatures in their "Produces" blocks match the call sites in Tasks 6 & 7. ✓
