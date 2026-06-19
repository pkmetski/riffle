# Note Glyph in Highlight Margin — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `"annotation-notes"` underline decoration with a margin note glyph, and make the highlight actions popup show a note read-first inline expansion.

**Architecture:** A new `NoteGlyphStyle`/`noteGlyphTemplate()` pair (mirroring `HighlightTintStyle`/`highlightTintTemplate()`) registers a `BOUNDS`-layout decoration that places a `::before` SVG icon to the left of the highlighted text's bounding box. A dedicated `"annotation-notes"` tap listener routes glyph taps to `openHighlightActions`. The `HighlightActionsPopup` gains local `noteExpanded` state that shows the full note text inline before entering the editor.

**Tech Stack:** Kotlin, Readium 3.3.0 `HtmlDecorationTemplate`, Compose, JUnit4

## Global Constraints

- Readium version: 3.3.0 — use only `HtmlDecorationTemplate.Layout.BOUNDS` or `BOXES`; no `userScripts` field exists on `EpubNavigatorFragment.Configuration`
- minSdk = 24 — avoid `java.util.Base64` (API 26+); use inline percent-encoded SVG data URI instead
- All new Kotlin goes in package `com.riffle.app.feature.reader`
- Run JVM tests with `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest`
- Never call `./gradlew :app:connectedDebugAndroidTest` directly — use `make harness-test`
- No `Co-Authored-By: Claude` in commits

---

### Task 1: `NoteGlyphStyle` + `noteGlyphTemplate()` + unit tests

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/NoteGlyphDecoration.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/NoteGlyphDecorationTest.kt`

**Interfaces:**
- Produces: `class NoteGlyphStyle : Decoration.Style, Parcelable` — marker style, no fields
- Produces: `fun noteGlyphTemplate(): HtmlDecorationTemplate` — BOUNDS layout, SVG `::before` in gutter
- Produces: `internal const val NOTE_GLYPH_SVG_DATA_URI: String` — percent-encoded SVG data URI

---

- [ ] **Step 1.1: Write the failing tests**

Create `app/src/test/kotlin/com/riffle/app/feature/reader/NoteGlyphDecorationTest.kt`:

```kotlin
@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.html.HtmlDecorationTemplate

class NoteGlyphDecorationTest {

    @Test
    fun `two NoteGlyphStyle instances are equal`() {
        assertEquals(NoteGlyphStyle(), NoteGlyphStyle())
    }

    @Test
    fun `NoteGlyphStyle is not equal to HighlightTintStyle`() {
        assertNotEquals(NoteGlyphStyle(), HighlightTintStyle(0xFF000000.toInt()))
    }

    @Test
    fun `noteGlyphTemplate uses BOUNDS layout`() {
        val template = noteGlyphTemplate()
        assertEquals(HtmlDecorationTemplate.Layout.BOUNDS, template.layout)
    }

    @Test
    fun `noteGlyphTemplate stylesheet positions glyph in the left gutter`() {
        // The ::before pseudo-element must be placed to the left of the bounding box.
        val stylesheet = noteGlyphTemplate().stylesheet ?: ""
        assertTrue("stylesheet must contain '::before'", stylesheet.contains("::before"))
        assertTrue("stylesheet must position glyph left of bounds via 'right: 100%'",
            stylesheet.contains("right: 100%"))
    }

    @Test
    fun `noteGlyphTemplate stylesheet references the SVG data URI`() {
        val stylesheet = noteGlyphTemplate().stylesheet ?: ""
        assertTrue("stylesheet must include SVG data URI background-image",
            stylesheet.contains("data:image/svg+xml"))
    }

    @Test
    fun `NoteGlyphStyle survives Parcelable round-trip`() {
        val original = NoteGlyphStyle()
        val parcel = android.os.Parcel.obtain()
        original.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val restored = NoteGlyphStyle.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        assertEquals(original, restored)
    }
}
```

- [ ] **Step 1.2: Run tests to confirm they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "*.NoteGlyphDecorationTest"
```

Expected: FAIL — `NoteGlyphStyle`, `noteGlyphTemplate`, `NOTE_GLYPH_SVG_DATA_URI` not defined.

- [ ] **Step 1.3: Implement `NoteGlyphDecoration.kt`**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/NoteGlyphDecoration.kt`:

```kotlin
package com.riffle.app.feature.reader

import android.os.Parcel
import android.os.Parcelable
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate

// SVG path from Icons.AutoMirrored.Outlined.StickyNote2 (Apache 2.0).
// Percent-encoded so it can be embedded directly in a CSS url() without base64.
// %3C/%3E = < / >
internal const val NOTE_GLYPH_SVG_DATA_URI =
    "data:image/svg+xml," +
    "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E" +
    "%3Cpath d='M19,5v9l-5,0,0,5H5V5H19M19,3H5C3.9,3,3,3.9,3,5v14" +
    "c0,1.1,.9,2,2,2h10l6,-6V5C21,3.9,20.1,3,19,3Z" +
    "M12,14H7v-2h5V14ZM17,10H7V8h10V10Z'/%3E" +
    "%3C/svg%3E"

private const val NOTE_GLYPH_CLASS = "riffle-note-glyph"

/**
 * Marker decoration style for noted highlights. No tint — the glyph is monochrome.
 * All noted highlights share the same icon regardless of highlight colour or theme.
 */
class NoteGlyphStyle : Decoration.Style, Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = Unit

    override fun equals(other: Any?): Boolean = other is NoteGlyphStyle

    override fun hashCode(): Int = NoteGlyphStyle::class.hashCode()

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<NoteGlyphStyle> =
            object : Parcelable.Creator<NoteGlyphStyle> {
                override fun createFromParcel(source: Parcel) = NoteGlyphStyle()
                override fun newArray(size: Int): Array<NoteGlyphStyle?> = arrayOfNulls(size)
            }
    }
}

/**
 * Decoration template for [NoteGlyphStyle]. A single transparent BOUNDS div sits over
 * the selection; its ::before pseudo-element is absolutely positioned to the left of the
 * div (right: 100%), placing the note icon in the left gutter alongside the text.
 * overflow: visible on the host div prevents the gutter icon from being clipped.
 */
fun noteGlyphTemplate(): HtmlDecorationTemplate =
    HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOUNDS,
        element = { _ -> """<div class="$NOTE_GLYPH_CLASS"/>""" },
        stylesheet = """
            .$NOTE_GLYPH_CLASS {
                background: none;
                overflow: visible;
                position: relative;
            }
            .$NOTE_GLYPH_CLASS::before {
                content: "";
                position: absolute;
                right: 100%;
                top: 0;
                margin-right: 4px;
                width: 16px;
                height: 16px;
                background-image: url("$NOTE_GLYPH_SVG_DATA_URI");
                background-size: contain;
                background-repeat: no-repeat;
                opacity: 0.55;
                pointer-events: none;
            }
        """.trimIndent(),
    )
```

- [ ] **Step 1.4: Run tests again — all should pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "*.NoteGlyphDecorationTest"
```

Expected: all 6 tests PASS.

Note: the Parcelable round-trip test requires the Android runtime — if it fails with `RuntimeException: Stub!`, remove it from this task and re-add it as an androidTest in Task 2.

- [ ] **Step 1.5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/NoteGlyphDecoration.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/NoteGlyphDecorationTest.kt
git commit -m "feat(annotations): NoteGlyphStyle + noteGlyphTemplate for margin note icon"
```

---

### Task 2: Wire the decoration — register template, swap Underline, add tap listener

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` (lines ~1649–1698)
- Modify: `app/src/test/kotlin/com/riffle/app/feature/reader/ReadaloudHighlightDecorationTest.kt`

**Interfaces:**
- Consumes: `NoteGlyphStyle` and `noteGlyphTemplate()` from Task 1
- Produces: `NoteGlyphStyle` template registered in `FormattingPreferencesMapper`; `"annotation-notes"` group uses `NoteGlyphStyle()`; dedicated tap listener routes glyph taps to `openHighlightActions`

---

- [ ] **Step 2.1: Extend the existing template-registration test**

In `app/src/test/kotlin/com/riffle/app/feature/reader/ReadaloudHighlightDecorationTest.kt`, extend `fragmentConfigurationRegistersHighlightTemplateAlongsideDefaults`:

```kotlin
@Test
fun fragmentConfigurationRegistersHighlightTemplateAlongsideDefaults() {
    val templates = FormattingPreferences().toFragmentConfiguration().decorationTemplates
    assertNotNull(templates[HighlightTintStyle::class])
    assertNotNull(templates[Decoration.Style.Highlight::class])
    assertNotNull(templates[NoteGlyphStyle::class])   // ← add this line
}
```

Run to confirm it fails:
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "*.ReadaloudHighlightDecorationTest"
```

Expected: FAIL — `NoteGlyphStyle` template not yet registered.

- [ ] **Step 2.2: Register `NoteGlyphStyle` template in `FormattingPreferencesMapper.kt`**

In `toFragmentConfiguration()`, the `decorationTemplates` block currently reads:

```kotlin
decorationTemplates = HtmlDecorationTemplates.defaultTemplates().apply {
    set(HighlightTintStyle::class, highlightTintTemplate())
},
```

Add one line:

```kotlin
decorationTemplates = HtmlDecorationTemplates.defaultTemplates().apply {
    set(HighlightTintStyle::class, highlightTintTemplate())
    set(NoteGlyphStyle::class, noteGlyphTemplate())
},
```

Run the test again — it should now pass:
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "*.ReadaloudHighlightDecorationTest"
```

Expected: PASS.

- [ ] **Step 2.3: Swap `Decoration.Style.Underline` → `NoteGlyphStyle()` in `EpubReaderScreen.kt`**

Find the `"annotation-notes"` LaunchedEffect (around line 1666–1674). Replace the `Decoration` construction:

**Before:**
```kotlin
val noteDecorations = noted.map { h ->
    Decoration(
        id = h.id,
        locator = h.locator,
        style = Decoration.Style.Underline(
            tint = HighlightColor.fromToken(h.color).readerTint(formattingPrefs.theme),
        ),
    )
}
```

**After:**
```kotlin
val noteDecorations = noted.map { h ->
    Decoration(
        id = h.id,
        locator = h.locator,
        style = NoteGlyphStyle(),
    )
}
```

- [ ] **Step 2.4: Add the `"annotation-notes"` decoration tap listener in `EpubReaderScreen.kt`**

After the existing `"annotations"` `DisposableEffect` (around line 1698), add:

```kotlin
// ---- Decoration tap listener (annotation-notes) ----------------------------------------
// Tapping the margin note glyph opens the same highlight-actions popup as tapping the
// highlight text. The glyph lives in the left gutter (outside the text hit area), so the
// "annotations" listener above does NOT fire for it — this dedicated listener is required.
DisposableEffect(fragmentRef.value) {
    val fragment = fragmentRef.value as? DecorableNavigator
    val listener = object : DecorableNavigator.Listener {
        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
            if (event.group != "annotation-notes") return false
            val container = containerRef.value ?: return false
            val rawRect = event.rect ?: return false
            val rect = rawRect.toWindowIntRect(container)
            currentOnOpenHighlightActions(event.decoration.id, rect)
            return true
        }
    }
    fragment?.addDecorationListener("annotation-notes", listener)
    onDispose { fragment?.removeDecorationListener(listener) }
}
```

- [ ] **Step 2.5: Run the full JVM test suite**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test
```

Expected: all tests pass (the suite includes `NoteGlyphDecorationTest` and `ReadaloudHighlightDecorationTest`).

- [ ] **Step 2.6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/ReadaloudHighlightDecorationTest.kt
git commit -m "feat(annotations): wire NoteGlyphStyle decoration and annotation-notes tap listener"
```

---

### Task 3: Read-first inline note expansion in `HighlightActionsPopup` + Compose tests

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/HighlightActionsSheet.kt`
- Create: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/HighlightActionsPopupTest.kt`

**Interfaces:**
- Consumes: `HighlightActionsPopup(anchorRect, selected, note, onPick, onDelete, onOpenNoteEditor, onDismiss)` — signature unchanged
- Produces: when `note != null`, tapping the note row expands it inline (no immediate editor); "Edit" button then calls `onOpenNoteEditor`

---

- [ ] **Step 3.1: Write the failing Compose tests**

Create `app/src/androidTest/kotlin/com/riffle/app/feature/reader/HighlightActionsPopupTest.kt`:

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.HighlightColor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighlightActionsPopupTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val stubRect = IntRect(100, 200, 300, 220)

    private fun showPopup(
        note: String? = null,
        onOpenNoteEditor: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            HighlightActionsPopup(
                anchorRect = stubRect,
                selected = HighlightColor.YELLOW,
                note = note,
                onPick = {},
                onDelete = {},
                onOpenNoteEditor = onOpenNoteEditor,
                onDismiss = onDismiss,
            )
        }
    }

    @Test
    fun noNote_tapNoteRow_callsOnOpenNoteEditor() {
        var called = false
        showPopup(note = null, onOpenNoteEditor = { called = true })
        composeTestRule.onNodeWithText("Add note").performClick()
        assertTrue("onOpenNoteEditor must be called directly when there is no note", called)
    }

    @Test
    fun existingNote_initialState_showsPreviewAndExpandIcon() {
        showPopup(note = "My detailed note text here")
        composeTestRule.onNodeWithText("Note").assertIsDisplayed()
        composeTestRule.onNodeWithText("My detailed note text here").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand note").assertIsDisplayed()
    }

    @Test
    fun existingNote_tapRow_expandsToFullTextAndEditButton() {
        showPopup(note = "My detailed note text here")
        composeTestRule.onNodeWithText("Note").performClick()
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Collapse note").assertIsDisplayed()
    }

    @Test
    fun existingNote_expanded_tapEditButton_callsOnOpenNoteEditor() {
        var called = false
        showPopup(note = "Some note", onOpenNoteEditor = { called = true })
        composeTestRule.onNodeWithText("Note").performClick()   // expand
        composeTestRule.onNodeWithText("Edit").performClick()   // open editor
        assertTrue("tapping Edit in expanded state must call onOpenNoteEditor", called)
    }
}
```

- [ ] **Step 3.2: Run to confirm they fail**

```
make harness-test
```

(These are in `androidTest` — they run on the harness AVD via `make harness-test`.)

Expected: FAIL — `Expand note` / `Collapse note` content descriptions not present yet.

- [ ] **Step 3.3: Update `HighlightActionsPopup` in `HighlightActionsSheet.kt`**

Add `var noteExpanded by remember { mutableStateOf(false) }` at the top of the composable body (before the `Popup` call), and add the missing import `androidx.compose.runtime.getValue`, `androidx.compose.runtime.setValue`.

Replace the note row (the `Row` from line ~139 to ~173) with:

```kotlin
HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable {
            when {
                note == null -> { onDismiss(); onOpenNoteEditor() }
                noteExpanded -> noteExpanded = false
                else -> noteExpanded = true
            }
        }
        .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = if (note != null) "Note" else "Add note",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (note != null && !noteExpanded) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (note != null && noteExpanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { onDismiss(); onOpenNoteEditor() },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Edit")
            }
        }
    }
    Icon(
        imageVector = when {
            note == null -> Icons.Outlined.Edit
            noteExpanded -> Icons.Filled.KeyboardArrowUp
            else -> Icons.Filled.KeyboardArrowDown
        },
        contentDescription = when {
            note == null -> null
            noteExpanded -> "Collapse note"
            else -> "Expand note"
        },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}
```

Add missing imports at the top of the file:

```kotlin
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
```

- [ ] **Step 3.4: Run the full JVM suite to catch any regressions**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test
```

Expected: all JVM tests pass.

- [ ] **Step 3.5: Run the Compose tests on the harness AVD**

```
make harness-test
```

Expected: `HighlightActionsPopupTest` — all 4 tests PASS.

- [ ] **Step 3.6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/HighlightActionsSheet.kt \
        app/src/androidTest/kotlin/com/riffle/app/feature/reader/HighlightActionsPopupTest.kt
git commit -m "feat(annotations): read-first inline note expansion in highlight actions popup"
```
