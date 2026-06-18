# Highlight palette + edit & delete — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the reader pick a highlight's colour from a fixed four-token palette (yellow default, green, blue, pink) and recolour or soft-delete an existing highlight, with the colour rendered legibly in both light and dark reader themes.

**Architecture:** A self-contained `HighlightColor` token enum in `core/domain` carries the four hues (matching readaloud's for visual consistency, but decoupled from `ReadaloudHighlightColor`). A `recolor` method is added down the existing store/DAO stack (no Room migration — `color`/`deleted`/`updatedAt` columns already exist). The readaloud tinted-box decoration style/template is generalised to a neutral shared name so persisted highlights get the same theme-aware alpha treatment. In the reader, a single bottom sheet (swatch row + delete) serves both creating and tapping a highlight.

**Tech Stack:** Kotlin, Room, Hilt, Jetpack Compose, Readium 3.0.0 (`DecorableNavigator`).

## Global Constraints

- Colour is a **token from {yellow, green, blue, pink}**, never a freeform hex; yellow is the default (ADR 0024).
- Reader theme owns the light/dark RGB mapping — a highlight must stay legible in any theme.
- Build/run gradle with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Run JVM suites with `./gradlew test` (module `:testDebugUnitTest` misses pure-JVM `:test`).
- Run connected/androidTest via `make harness-test`; never `./gradlew connectedDebugAndroidTest` directly. `core:database` MigrationTest/AnnotationDaoTest are connected tests — pin them to a self-booted Harness AVD via `ANDROID_SERIAL`.
- No `Co-Authored-By: Claude` trailer; no "Generated with Claude Code" footer; never push without explicit ask.

---

### Task 1: `HighlightColor` token enum (core/domain)

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/HighlightColor.kt`
- Test: `core/domain/src/test/kotlin/com/riffle/core/domain/HighlightColorTest.kt`

**Interfaces:**
- Produces: `enum class HighlightColor(val token: String, val argb: Int)` with members `YELLOW, GREEN, BLUE, PINK`; `HighlightColor.DEFAULT = YELLOW`; `HighlightColor.fromToken(token: String?): HighlightColor`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightColorTest {

    @Test
    fun `every colour round-trips through its token`() {
        HighlightColor.entries.forEach { color ->
            assertEquals(color, HighlightColor.fromToken(color.token))
        }
    }

    @Test
    fun `tokens are the lowercase enum names`() {
        assertEquals("yellow", HighlightColor.YELLOW.token)
        assertEquals("green", HighlightColor.GREEN.token)
        assertEquals("blue", HighlightColor.BLUE.token)
        assertEquals("pink", HighlightColor.PINK.token)
    }

    @Test
    fun `default is yellow`() {
        assertEquals(HighlightColor.YELLOW, HighlightColor.DEFAULT)
    }

    @Test
    fun `unknown or null token falls back to the default`() {
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromToken("purple"))
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromToken(null))
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromToken(""))
    }

    @Test
    fun `hues match the shared readaloud palette values`() {
        assertEquals(0xFFFBBF24.toInt(), HighlightColor.YELLOW.argb)
        assertEquals(0xFF34D399.toInt(), HighlightColor.GREEN.argb)
        assertEquals(0xFF38BDF8.toInt(), HighlightColor.BLUE.argb)
        assertEquals(0xFFFB7185.toInt(), HighlightColor.PINK.argb)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:domain:test --tests "com.riffle.core.domain.HighlightColorTest"`
Expected: FAIL — `HighlightColor` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.riffle.core.domain

/**
 * The fixed annotation-highlight palette (ADR 0024): a colour is one of these four tokens, never a
 * freeform hex. [token] is the lowercase string persisted in `AnnotationEntity.color`; [argb] is the
 * full-opacity base hue (the reader theme bakes in per-theme alpha at render time).
 *
 * Self-contained on purpose: the hues match the readaloud palette for visual consistency, but
 * annotations keep their own four-token vocabulary (no PURPLE, yellow default) and their own synced
 * persistence, decoupled from [ReadaloudHighlightColor].
 */
enum class HighlightColor(val token: String, val argb: Int) {
    YELLOW("yellow", 0xFFFBBF24.toInt()),
    GREEN("green", 0xFF34D399.toInt()),
    BLUE("blue", 0xFF38BDF8.toInt()),
    PINK("pink", 0xFFFB7185.toInt());

    companion object {
        val DEFAULT = YELLOW

        /** Map a stored token back to a colour; unknown/null falls back to [DEFAULT] (sync forward-compat). */
        fun fromToken(token: String?): HighlightColor =
            entries.firstOrNull { it.token == token } ?: DEFAULT
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:domain:test --tests "com.riffle.core.domain.HighlightColorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/HighlightColor.kt core/domain/src/test/kotlin/com/riffle/core/domain/HighlightColorTest.kt
git commit -m "feat(annotations): add fixed HighlightColor palette token (#70)"
```

---

### Task 2: `recolor` DAO query (core/database)

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/AnnotationDao.kt`
- Test: `core/database/src/androidTest/kotlin/com/riffle/core/database/AnnotationDaoTest.kt` (extend)

**Interfaces:**
- Produces: `suspend fun AnnotationDao.recolor(id: String, color: String, updatedAt: Long, deviceId: String)`.

- [ ] **Step 1: Write the failing test** — append to `AnnotationDaoTest`:

```kotlin
    @Test
    fun recolor_updatesColourAndBumpsUpdatedAtAndDevice() = runTest {
        dao.upsert(highlight("h1", createdAt = 1000L))

        dao.recolor("h1", color = "green", updatedAt = 2000L, deviceId = "device-B")

        val row = dao.getById("h1")
        assertEquals("green", row?.color)
        assertEquals(2000L, row?.updatedAt)
        assertEquals("device-B", row?.lastModifiedByDeviceId)
        // The live query still returns it (recolour is not a delete).
        assertEquals(listOf("h1"), dao.observeForItem("abs1", "item1").first().map { it.id })
    }
```

- [ ] **Step 2: Run test to verify it fails**

Boot the Harness AVD and run pinned (per the connected-test constraints):
```bash
ANDROID_SERIAL=<harness-serial> JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :core:database:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.riffle.core.database.AnnotationDaoTest
```
Expected: FAIL — `recolor` unresolved.

- [ ] **Step 3: Add the query** — in `AnnotationDao`, after `tombstone`:

```kotlin
    /** Recolour an annotation in place, bumping updatedAt + provenance so the change can propagate. */
    @Query("UPDATE annotations SET color = :color, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id")
    suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String)
```

- [ ] **Step 4: Run test to verify it passes**

Run the same pinned command from Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/database/src/main/kotlin/com/riffle/core/database/AnnotationDao.kt core/database/src/androidTest/kotlin/com/riffle/core/database/AnnotationDaoTest.kt
git commit -m "feat(annotations): add recolor query to AnnotationDao (#70)"
```

---

### Task 3: `recolor` in the store (domain + data)

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/AnnotationStore.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationStoreImpl.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/AnnotationStoreImplTest.kt`

**Interfaces:**
- Consumes: `AnnotationDao.recolor(...)` (Task 2), `HighlightColor` (Task 1).
- Produces: `suspend fun AnnotationStore.recolor(id: String, color: String)`.

- [ ] **Step 1: Write the failing test** (pure JVM, fake DAO + fake DeviceIdStore):

```kotlin
package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.DeviceIdStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationStoreImplTest {

    private val rows = MutableStateFlow<List<AnnotationEntity>>(emptyList())

    private val dao = object : AnnotationDao {
        override fun observeForItem(serverId: String, itemId: String): Flow<List<AnnotationEntity>> =
            rows.map { all -> all.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted } }

        override suspend fun getForItem(serverId: String, itemId: String): List<AnnotationEntity> =
            rows.value.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }

        override suspend fun getById(id: String): AnnotationEntity? = rows.value.firstOrNull { it.id == id }

        override suspend fun upsert(entity: AnnotationEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }

        override suspend fun tombstone(id: String, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(deleted = true, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
            }
        }

        override suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(color = color, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
            }
        }
    }

    private val deviceIdStore = object : DeviceIdStore {
        override suspend fun getOrCreate(): String = "device-X"
    }

    private var now = 1000L

    private fun store() = AnnotationStoreImpl(
        dao = dao,
        deviceIdStore = deviceIdStore,
        clock = { now },
        idGenerator = { "fixed-id" },
    )

    @Test
    fun `createHighlight persists the chosen colour token`() = runTest {
        val created = store().createHighlight(
            serverId = "abs1", itemId = "item1", cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "t", chapterHref = "c.xhtml", color = "green",
        )
        assertEquals("green", created.color)
        assertEquals("green", dao.getById(created.id)?.color)
    }

    @Test
    fun `recolor updates the colour and bumps updatedAt and device`() = runTest {
        val s = store()
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml")
        now = 5000L

        s.recolor(created.id, "blue")

        val row = dao.getById(created.id)
        assertEquals("blue", row?.color)
        assertEquals(5000L, row?.updatedAt)
        assertEquals("device-X", row?.lastModifiedByDeviceId)
    }

    @Test
    fun `tombstoned highlights are excluded from observeHighlights (and thus from rendering)`() = runTest {
        val s = store()
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml")
        assertEquals(1, s.observeHighlights("abs1", "item1").first().size)

        s.delete(created.id)

        assertTrue(s.observeHighlights("abs1", "item1").first().isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:data:test --tests "com.riffle.core.data.AnnotationStoreImplTest"`
Expected: FAIL — `recolor` not a member of `AnnotationStore`.

- [ ] **Step 3a: Add to the interface** — in `AnnotationStore`, after `delete`:

```kotlin
    /** Recolour an existing highlight in place, bumping its updatedAt. */
    suspend fun recolor(id: String, color: String)
```

- [ ] **Step 3b: Implement** — in `AnnotationStoreImpl`, after `delete`:

```kotlin
    override suspend fun recolor(id: String, color: String) {
        dao.recolor(id, color = color, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:data:test --tests "com.riffle.core.data.AnnotationStoreImplTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/AnnotationStore.kt core/data/src/main/kotlin/com/riffle/core/data/AnnotationStoreImpl.kt core/data/src/test/kotlin/com/riffle/core/data/AnnotationStoreImplTest.kt
git commit -m "feat(annotations): recolor in the AnnotationStore (#70)"
```

---

### Task 4: Generalise the tinted-highlight rendering machinery (app)

Rename the readaloud-specific decoration style/template to a neutral shared name and extract the per-theme alpha helper. Behaviour is unchanged — this is the reuse seam both the readaloud and annotation paths share.

**Files:**
- Rename + edit: `app/src/main/kotlin/com/riffle/app/feature/reader/ReadaloudHighlightDecoration.kt` → `HighlightDecoration.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt:90`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt:1413` (readaloud apply site)
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/HighlightTintTest.kt`

**Interfaces:**
- Consumes: `HighlightColor` (Task 1), `ReaderTheme` (`com.riffle.core.domain`).
- Produces:
  - `class HighlightTintStyle(@ColorInt val tint: Int)` (was `ReadaloudHighlightStyle`).
  - `fun highlightTintTemplate(): HtmlDecorationTemplate` (was `readaloudHighlightTemplate()`).
  - `fun tintForTheme(@ColorInt argb: Int, theme: ReaderTheme): Int`.
  - `fun HighlightColor.readerTint(theme: ReaderTheme): Int`.
  - `fun ReadaloudHighlightColor.readerTint(theme: ReaderTheme): Int` (kept, delegates to `tintForTheme`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.riffle.app.feature.reader

import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightTintTest {

    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF
    private fun rgb(argb: Int) = argb and 0x00FFFFFF

    @Test
    fun `dark themes use the stronger alpha, light and sepia the lighter one`() {
        val argb = HighlightColor.GREEN.argb
        assertEquals(0x73, alpha(tintForTheme(argb, ReaderTheme.Dark)))
        assertEquals(0x73, alpha(tintForTheme(argb, ReaderTheme.DarkDim)))
        assertEquals(0x4D, alpha(tintForTheme(argb, ReaderTheme.Light)))
        assertEquals(0x4D, alpha(tintForTheme(argb, ReaderTheme.Sepia)))
    }

    @Test
    fun `tint preserves the base hue and only swaps the alpha channel`() {
        val tint = HighlightColor.PINK.readerTint(ReaderTheme.Dark)
        assertEquals(rgb(HighlightColor.PINK.argb), rgb(tint))
        assertEquals(0x73, alpha(tint))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.HighlightTintTest"`
Expected: FAIL — `tintForTheme` unresolved.

- [ ] **Step 3a: Rename the file and generalise its contents.** `git mv` then edit:

```bash
git mv app/src/main/kotlin/com/riffle/app/feature/reader/ReadaloudHighlightDecoration.kt \
       app/src/main/kotlin/com/riffle/app/feature/reader/HighlightDecoration.kt
```

Replace the file body with (same alphas, same geometry — only names generalised + the shared helper and the two `readerTint`s added):

```kotlin
package com.riffle.app.feature.reader

import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ReadaloudHighlightColor
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.toCss

// Readium's default highlight template (Style.Highlight) bakes a FIXED alpha (0.3) into the CSS and
// ignores the tint's own alpha channel — too faint on the Dark reading theme (0.3 of a colour over a
// black page is barely visible behind the white body text). Both the readaloud "now speaking"
// highlight and the persisted annotation highlights use this OWN style + template instead, so the
// fill opacity can vary per theme (the template honours the tint's alpha; the caller bakes in the
// strength via tintForTheme()).

// Fill opacity baked into the tint's alpha channel per reading theme. Dark pages need a stronger
// alpha so the highlight reads as a clear selection box behind the white body text.
internal const val HIGHLIGHT_ALPHA_DARK = 0x73 // ~45%
internal const val HIGHLIGHT_ALPHA_LIGHT = 0x4D // ~30%

/** Shared decoration style for tinted highlights (readaloud "now speaking" + persisted annotations). */
class HighlightTintStyle(
    @ColorInt override val tint: Int,
) : Decoration.Style, Decoration.Style.Tinted {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
    }

    // Value semantics so Readium's decoration diff treats an unchanged tint as unchanged.
    override fun equals(other: Any?): Boolean =
        this === other || (other is HighlightTintStyle && other.tint == tint)

    override fun hashCode(): Int = tint

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<HighlightTintStyle> =
            object : Parcelable.Creator<HighlightTintStyle> {
                override fun createFromParcel(source: Parcel) = HighlightTintStyle(source.readInt())
                override fun newArray(size: Int): Array<HighlightTintStyle?> = arrayOfNulls(size)
            }
    }
}

private const val HIGHLIGHT_TINT_CLASS = "riffle-highlight-tint"

/**
 * Template for [HighlightTintStyle]. Geometry mirrors Readium's built-in highlight (BOXES layout,
 * 1px horizontal padding, 3px corner radius) so positioning is unchanged; the only difference is the
 * fill opacity comes from the tint's alpha channel rather than a fixed value.
 */
fun highlightTintTemplate(): HtmlDecorationTemplate =
    HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOXES,
        element = { decoration ->
            val tint = (decoration.style as? Decoration.Style.Tinted)?.tint ?: Color.YELLOW
            """<div class="$HIGHLIGHT_TINT_CLASS" style="background-color: ${tint.toCss()} !important;"/>"""
        },
        stylesheet = """
            .$HIGHLIGHT_TINT_CLASS {
                margin: 0px -1px 0 0;
                padding: 0 2px 0 0;
                border-radius: 3px;
                box-sizing: border-box;
            }
        """.trimIndent(),
    )

/**
 * Bake the per-[theme] fill opacity into [argb]'s alpha channel. Dark/DarkDim pages use a stronger
 * alpha so the highlight reads as a clear box behind white body text; light/sepia use Readium's
 * usual ~30% so dark body text stays legible.
 */
@ColorInt
fun tintForTheme(@ColorInt argb: Int, theme: ReaderTheme): Int {
    val alpha = when (theme) {
        ReaderTheme.Dark, ReaderTheme.DarkDim -> HIGHLIGHT_ALPHA_DARK
        else -> HIGHLIGHT_ALPHA_LIGHT
    }
    return (argb and 0x00FFFFFF) or (alpha shl 24)
}

/** The tint to paint a persisted-annotation highlight with on the given reading [theme]. */
@ColorInt
fun HighlightColor.readerTint(theme: ReaderTheme): Int = tintForTheme(argb, theme)

/** The tint to paint the readaloud "now speaking" highlight with on the given reading [theme]. */
@ColorInt
fun ReadaloudHighlightColor.readerTint(theme: ReaderTheme): Int = tintForTheme(argb, theme)
```

- [ ] **Step 3b: Update the template registration** — `FormattingPreferencesMapper.kt:90`:

```kotlin
        decorationTemplates = HtmlDecorationTemplates.defaultTemplates().apply {
            set(HighlightTintStyle::class, highlightTintTemplate())
        },
```

- [ ] **Step 3c: Update the readaloud apply site** — `EpubReaderScreen.kt:1413`, change `ReadaloudHighlightStyle(` to `HighlightTintStyle(`. (The `.readerTint(...)` call there is unchanged — `ReadaloudHighlightColor.readerTint` still exists.)

- [ ] **Step 4: Run test + build to verify**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.HighlightTintTest" :app:compileDebugKotlin
```
Expected: test PASS and Kotlin compiles (no stale `ReadaloudHighlightStyle`/`readaloudHighlightTemplate` references — grep to be sure: `rg -n "ReadaloudHighlightStyle|readaloudHighlightTemplate|READALOUD_HIGHLIGHT_ALPHA" app/src`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/HighlightDecoration.kt app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt app/src/test/kotlin/com/riffle/app/feature/reader/HighlightTintTest.kt
git commit -m "refactor(reader): generalise readaloud highlight style into shared HighlightTintStyle (#70)"
```

---

### Task 5: Render persisted highlights theme-aware (app)

Replace the stub `highlightTint()` + default `Style.Highlight` with the shared theme-aware style, and re-tint when the reader theme changes.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` (annotations decoration `LaunchedEffect` ~1436–1457; delete `highlightTint` ~1858)

**Interfaces:**
- Consumes: `HighlightTintStyle`, `HighlightColor.readerTint(theme)` (Task 4), `HighlightColor.fromToken` (Task 1).

- [ ] **Step 1: Locate the reader-theme value in scope.** In `EpubReaderColumn` (the composable holding the annotations `LaunchedEffect`), find the formatting prefs already collected (search `formattingPrefs` / `currentFormattingPrefs` near the readaloud apply site at ~1410, which already does `.readerTint(formattingPrefs.theme)`). Use that same `formattingPrefs.theme`.

- [ ] **Step 2: Rewrite the annotations decoration effect** (~1436):

```kotlin
    val hasHighlightDecorations = remember { mutableStateOf(false) }
    LaunchedEffect(highlightRenders, formattingPrefs.theme, reflowGeneration, pageLoadGeneration.value) {
        val fragment = fragmentRef.value as? DecorableNavigator ?: return@LaunchedEffect
        if (highlightRenders.isEmpty()) {
            if (!hasHighlightDecorations.value) return@LaunchedEffect
            withContext(Dispatchers.Main) {
                fragment.applyDecorations(emptyList(), group = "annotations")
            }
            hasHighlightDecorations.value = false
            return@LaunchedEffect
        }
        val decorations = highlightRenders.map { h ->
            Decoration(
                id = h.id,
                locator = h.locator,
                style = HighlightTintStyle(
                    tint = HighlightColor.fromToken(h.color).readerTint(formattingPrefs.theme),
                ),
            )
        }
        withContext(Dispatchers.Main) {
            fragment.applyDecorations(decorations, group = "annotations")
        }
        hasHighlightDecorations.value = true
    }
```

(If the in-scope name is `currentFormattingPrefs` or similar rather than `formattingPrefs`, use whatever the readaloud apply site at ~1410 uses for `.theme`.)

- [ ] **Step 3: Delete the stub** — remove the `highlightTint` function at ~1858:

```kotlin
private fun highlightTint(color: String): Int =
    android.graphics.Color.parseColor("#FFFDE68A")
```

- [ ] **Step 4: Verify it compiles**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL; `rg -n "highlightTint\b" app/src` returns nothing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(annotations): render persisted highlights with theme-aware palette tint (#70)"
```

---

### Task 6: Swatch row + highlight-actions sheet (app)

A reusable swatch row and a bottom sheet exposing recolour + delete. Pure composables wired by Task 8.

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/HighlightActionsSheet.kt`

**Interfaces:**
- Consumes: `HighlightColor` (Task 1).
- Produces:
  - `@Composable fun HighlightSwatchRow(selected: HighlightColor?, onPick: (HighlightColor) -> Unit, modifier: Modifier = Modifier)`.
  - `@Composable fun HighlightActionsSheet(selected: HighlightColor?, onPick: (HighlightColor) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit)`.

- [ ] **Step 1: Create the file**

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.HighlightColor

/**
 * A row of the four highlight swatches. The selected swatch gets an onSurface ring + a centred
 * checkmark (reads clearly in both themes); the 4dp padding is always reserved so the row doesn't
 * shift on selection. Modelled on the readaloud settings picker for visual consistency.
 */
@Composable
fun HighlightSwatchRow(
    selected: HighlightColor?,
    onPick: (HighlightColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HighlightColor.entries.forEach { color ->
            val isSelected = color == selected
            val swatchColor = Color(color.argb.toLong() and 0xFFFFFFFFL)
            androidx.compose.foundation.layout.Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onPick(color) }
                    .then(
                        if (isSelected) Modifier.border(2.dp, androidx.compose.material3.MaterialTheme.colorScheme.onSurface, CircleShape)
                        else Modifier
                    )
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(swatchColor)
                    .semantics {
                        contentDescription = color.token.replaceFirstChar { it.uppercase() } +
                            " highlight" + if (isSelected) ", selected" else ""
                    },
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xDD000000),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightActionsSheet(
    selected: HighlightColor?,
    onPick: (HighlightColor) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Highlight", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            HighlightSwatchRow(selected = selected, onPick = onPick)
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/HighlightActionsSheet.kt
git commit -m "feat(annotations): highlight-actions sheet with palette swatches + delete (#70)"
```

---

### Task 7: ViewModel recolour + delete + expose created id (app)

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` (createHighlight ~993, add recolor/deleteHighlight)

**Interfaces:**
- Consumes: `AnnotationStore.recolor`, `AnnotationStore.delete` (Tasks 3), `HighlightColor` (Task 1).
- Produces:
  - `fun recolorHighlight(id: String, color: HighlightColor)`.
  - `fun deleteHighlight(id: String)`.
  - `createHighlight(selectionLocator: Locator)` made to publish the new highlight's id (see Step 2).

- [ ] **Step 1: Read `createHighlight` (~993–1017)** to confirm it calls `annotationStore.createHighlight(...)` and gets back an `Annotation`. Confirm `annotationServerId` is the resolved server id used for create.

- [ ] **Step 2: Expose the just-created id.** Add a one-shot event the screen observes to open the sheet. Near the other state flows (~313):

```kotlin
    private val _highlightToEdit = MutableStateFlow<String?>(null)
    /** Id of a highlight whose actions sheet should be open (just-created or tapped), else null. */
    val highlightToEdit: StateFlow<String?> = _highlightToEdit

    fun openHighlightActions(id: String) { _highlightToEdit.value = id }
    fun dismissHighlightActions() { _highlightToEdit.value = null }
```

In `createHighlight`, after the store call returns the created `Annotation` (capture it as `val created = annotationStore.createHighlight(...)`), set `_highlightToEdit.value = created.id`.

- [ ] **Step 3: Add recolour + delete.** After `createHighlight`:

```kotlin
    /** Recolour an existing highlight; observeHighlights re-emits → decoration re-applies. */
    fun recolorHighlight(id: String, color: com.riffle.core.domain.HighlightColor) {
        viewModelScope.launch { annotationStore.recolor(id, color.token) }
    }

    /** Soft-delete a highlight; observeHighlights re-emits without it → decoration is removed. */
    fun deleteHighlight(id: String) {
        viewModelScope.launch {
            annotationStore.delete(id)
            if (_highlightToEdit.value == id) _highlightToEdit.value = null
        }
    }
```

(Confirm `viewModelScope` + `kotlinx.coroutines.launch` are already imported; the existing `createHighlight` uses them.)

- [ ] **Step 4: Verify it compiles**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(annotations): viewmodel recolor/delete + open-actions event (#70)"
```

---

### Task 8: Wire the reader — enable UI, open sheet on create + on tap (app)

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` (flag ~117; screen collectors ~238/344; column ~908–920 params, decoration listener near annotations effect ~1436)

**Interfaces:**
- Consumes: `viewModel.highlightToEdit`, `viewModel.openHighlightActions`, `viewModel.dismissHighlightActions`, `viewModel.recolorHighlight`, `viewModel.deleteHighlight` (Task 7); `HighlightActionsSheet` (Task 6); `HighlightColor.fromToken` (Task 1); `DecorableNavigator.addDecorationListener` / `DecorableNavigator.Listener` / `OnActivatedEvent` (Readium).

- [ ] **Step 1: Enable the feature flag** — `EpubReaderScreen.kt:117`:

```kotlin
private const val ANNOTATIONS_UI_ENABLED = true
```

- [ ] **Step 2: Collect sheet state in the screen composable** (~238, beside `highlightRenders`):

```kotlin
    val highlightToEdit by viewModel.highlightToEdit.collectAsState()
```

Pass the needed lambdas/state down to the column composable (`EpubReaderColumn`, the one taking `highlightRenders` at ~913). Add parameters:

```kotlin
    highlightToEdit: String?,
    onOpenHighlightActions: (String) -> Unit,
    onDismissHighlightActions: () -> Unit,
    onRecolorHighlight: (String, com.riffle.core.domain.HighlightColor) -> Unit,
    onDeleteHighlight: (String) -> Unit,
```

and wire them at the call site (~344) to `highlightToEdit`, `viewModel::openHighlightActions`, `viewModel::dismissHighlightActions`, `viewModel::recolorHighlight`, `viewModel::deleteHighlight`.

- [ ] **Step 3: Register a decoration tap listener** for the "annotations" group. Add a `DisposableEffect` keyed on `fragmentRef.value` near the annotations decoration effect (~1436):

```kotlin
    val currentOnOpenHighlightActions by rememberUpdatedState(onOpenHighlightActions)
    DisposableEffect(fragmentRef.value) {
        val fragment = fragmentRef.value as? DecorableNavigator
        val listener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                if (event.group != "annotations") return false
                currentOnOpenHighlightActions(event.decoration.id)
                return true
            }
        }
        fragment?.addDecorationListener("annotations", listener)
        onDispose { fragment?.removeDecorationListener(listener) }
    }
```

(Import `org.readium.r2.navigator.DecorableNavigator`; `OnActivatedEvent`/`Listener` are nested types on it.)

- [ ] **Step 4: Show the sheet.** Where other reader overlays/sheets are composed in the column, add:

```kotlin
    if (highlightToEdit != null) {
        val current = highlightRenders.firstOrNull { it.id == highlightToEdit }
        HighlightActionsSheet(
            selected = current?.let { com.riffle.core.domain.HighlightColor.fromToken(it.color) },
            onPick = { color -> onRecolorHighlight(highlightToEdit!!, color) },
            onDelete = { onDeleteHighlight(highlightToEdit!!) },
            onDismiss = onDismissHighlightActions,
        )
    }
```

- [ ] **Step 5: Verify it compiles**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual / harness verification** (no automated reader-UI harness for this flow):
  - Select text → tap **Highlight**: a highlight appears and the actions sheet opens. Pick green → highlight turns green in place. Reopen the book → still green.
  - Tap an existing highlight → sheet opens → **Delete** → decoration disappears. Reopen the book → still gone.
  - Switch to the Dark theme → existing highlights remain clearly legible behind body text.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(annotations): palette picker + edit/delete sheet in the reader (#70)"
```

---

### Task 9: Amend ADR 0024 (docs)

**Files:**
- Modify: `docs/adr/0024-annotations-anchor-on-abs-epub-cfi.md`

- [ ] **Step 1: Append an amendment note** recording the concrete palette + rendering decision:

```markdown
## Amendment (2026-06-17): colour palette + rendering (#70)

The highlight colour is one of four tokens — `yellow` (default), `green`, `blue`, `pink` —
modelled by `HighlightColor` (`core/domain`). Tokens are stored verbatim in
`AnnotationEntity.color`; an unknown/missing token resolves to yellow (sync forward-compat).
The four hues match the readaloud palette for visual consistency but are a separate, smaller
vocabulary (no PURPLE, yellow default) decoupled from `ReadaloudHighlightColor`.

Rendering reuses the shared `HighlightTintStyle` decoration + `tintForTheme()`: the reader theme
bakes per-theme alpha into the base hue (~45% on Dark/DarkDim, ~30% on Light/Sepia) so a highlight
stays legible in any theme. Recolour updates the row in place (bumping `updatedAt`); delete sets the
`deleted` tombstone (bumping `updatedAt`) and tombstoned rows are excluded from the live query, so
they never render.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0024-annotations-anchor-on-abs-epub-cfi.md
git commit -m "docs(adr): record highlight palette + rendering decision (#70)"
```

---

### Task 10: Full verification

- [ ] **Step 1: JVM suites**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`
Expected: BUILD SUCCESSFUL (new `HighlightColorTest`, `AnnotationStoreImplTest`, `HighlightTintTest` green; nothing else regressed).

- [ ] **Step 2: AnnotationDaoTest (connected, pinned)**

Boot the Harness AVD and run pinned:
```bash
ANDROID_SERIAL=<harness-serial> JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :core:database:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.riffle.core.database.AnnotationDaoTest
```
Expected: PASS (incl. the new `recolor` test).

- [ ] **Step 3: Confirm no migration was needed** — `git diff --stat origin/main -- core/database/schemas` is empty; `@Database version` unchanged.

- [ ] **Step 4: Manual reader pass** — repeat Task 8 Step 6 on a device/emulator if not already done.

---

## Self-review notes

- **Spec coverage:** palette on create (Task 8 Step 4 + Task 7 create event) and edit (Task 8 Step 3/4); token storage + theme-aware RGB (Tasks 1, 4, 5); recolour-in-place bumping `updatedAt` (Tasks 2, 3); soft-delete + decoration removal + persists after reopen (Tasks 3, 7, 8; observeHighlights filters deleted); tests for token round-trip + tombstone-excluded-from-rendering (Tasks 1, 3) and recolour (Tasks 2, 3). All acceptance criteria mapped.
- **Type consistency:** `HighlightColor` (`token`, `argb`, `fromToken`, `DEFAULT`), `HighlightTintStyle`, `highlightTintTemplate()`, `tintForTheme`, `readerTint`, `recolorHighlight`/`deleteHighlight`/`highlightToEdit` used consistently across tasks.
- **No migration:** `color`/`deleted`/`updatedAt` pre-exist; verified in Task 10 Step 3.
```
