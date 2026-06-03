# Readaloud Indicator & Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the readaloud "headphones" icon with a purpose-drawn "book + broadcast" glyph; surface a readaloud indicator on library grid covers, item detail, and the reader; add a dedicated "Download readaloud" button on matched ABS item detail; and make the reader's readaloud control show-but-disabled until the bundle is downloaded.

**Architecture:** A single `VectorDrawable` (`ic_readaloud.xml`) is referenced via `painterResource` everywhere the old `Icons.Filled.Headphones` was used. The readaloud bundle is keyed by **Storyteller book id** (resolved from the book's `ReadaloudLink`), not the ABS item id — both the detail download button and the reader control resolve the link and operate on the linked Storyteller identity, so they share one on-device bundle. The download path gains a server-explicit variant so the ABS detail screen can fetch from the linked Storyteller server (not the active ABS server).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Kotlin coroutines/Flow, JUnit4 + Compose UI test (`createComposeRule`), Robolectric-free pure JVM unit tests under `src/test`.

**Spec:** `docs/superpowers/specs/2026-06-02-readaloud-indicator-icon-design.md`

---

## File Structure

**Create:**
- `app/src/main/res/drawable/ic_readaloud.xml` — the book+broadcast glyph.
- `app/src/main/kotlin/com/riffle/app/feature/library/ReadaloudDownloadButton.kt` — round download control (arrow + glyph badge), mirrors `DownloadButton`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/ReadaloudControlState.kt` — pure helper deciding control visible/enabled.
- `app/src/test/kotlin/com/riffle/app/feature/reader/ReadaloudControlStateTest.kt`
- `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailReadaloudDownloadTest.kt`

**Modify:**
- `core/domain/.../ReadaloudAudioRepository.kt` — add server-explicit `downloadAudio(bookId, serverId, onProgress)`.
- `core/data/.../ReadaloudAudioRepositoryImpl.kt` — implement it; existing one delegates.
- Any fakes implementing `ReadaloudAudioRepository` — add the new method.
- `app/.../feature/reader/EpubReaderViewModel.kt` — inject `ReadaloudLinkRepository`; resolve link; compute `readaloudVisible`/`readaloudAvailable` keyed by Storyteller book id; re-key audio refs.
- `app/.../feature/reader/EpubReaderScreen.kt` — show-but-disable control; new icon.
- `app/.../feature/library/LibraryItemsScreen.kt` — swap list-badge icon; thread `linkedItemIds` to grid; cover overlay; remove C/D badges.
- `app/.../feature/library/LibraryItemDetailViewModel.kt` — resolve link; readaloud download state; download/remove; drop `AbsHasReadaloud` footer.
- `app/.../feature/library/LibraryItemDetailScreen.kt` — title indicator; readaloud download button; footer icon swap.

---

## Task 1: Add the `ic_readaloud` vector drawable

**Files:**
- Create: `app/src/main/res/drawable/ic_readaloud.xml`

- [ ] **Step 1: Create the drawable**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Readaloud (synced narration): open book with sound broadcasting upward. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#00000000"
        android:strokeColor="#FF000000"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:pathData="M12,11.2c-1.5,-1 -3.6,-1.6 -5.7,-1.6 -0.6,0 -1.1,0.4 -1.1,1v6.3c0,0.5 0.5,0.9 1.1,0.9 2.1,0 4.2,0.6 5.7,1.6 1.5,-1 3.6,-1.6 5.7,-1.6 0.6,0 1.1,-0.4 1.1,-0.9v-6.3c0,-0.6 -0.5,-1 -1.1,-1 -2.1,0 -4.2,0.6 -5.7,1.6z" />
    <path
        android:fillColor="#00000000"
        android:strokeColor="#FF000000"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:pathData="M12,11.2V18.1" />
    <path
        android:fillColor="#00000000"
        android:strokeColor="#FF000000"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:pathData="M9,8.2c1.9,-1.1 4.1,-1.1 6,0" />
    <path
        android:fillColor="#00000000"
        android:strokeColor="#FF000000"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:pathData="M7.4,5.6c2.8,-1.6 6.4,-1.6 9.2,0" />
</vector>
```

The stroke color is solid black; every call site renders it through `Icon(..., tint = …)`, which applies a `ColorFilter`, so the actual color follows the theme. (`fillColor="#00000000"` keeps it stroke-only.)

- [ ] **Step 2: Verify it compiles / inflates**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (a malformed `pathData` fails resource processing here).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/drawable/ic_readaloud.xml
git commit -m "feat(readaloud): add book+broadcast vector icon"
```

---

## Task 2: Pure helper for the reader control's visible/enabled state

A tiny pure function so the show-but-disable rule (spec §6) is unit-testable without the heavy `EpubReaderViewModel`.

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ReadaloudControlState.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/ReadaloudControlStateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadaloudControlStateTest {
    @Test fun storyteller_book_is_visible_and_enabled() {
        assertEquals(
            ReadaloudControlState(visible = true, enabled = true),
            readaloudControlState(isStoryteller = true, isMatchedAbs = false, bundlePresent = false),
        )
    }

    @Test fun unmatched_abs_book_hides_the_control() {
        assertEquals(
            ReadaloudControlState(visible = false, enabled = false),
            readaloudControlState(isStoryteller = false, isMatchedAbs = false, bundlePresent = false),
        )
    }

    @Test fun matched_abs_without_bundle_is_visible_but_disabled() {
        assertEquals(
            ReadaloudControlState(visible = true, enabled = false),
            readaloudControlState(isStoryteller = false, isMatchedAbs = true, bundlePresent = false),
        )
    }

    @Test fun matched_abs_with_bundle_is_visible_and_enabled() {
        assertEquals(
            ReadaloudControlState(visible = true, enabled = true),
            readaloudControlState(isStoryteller = false, isMatchedAbs = true, bundlePresent = true),
        )
    }
}
```

- [ ] **Step 2: Run it; verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.ReadaloudControlStateTest"`
Expected: FAIL — unresolved reference `ReadaloudControlState` / `readaloudControlState`.

- [ ] **Step 3: Implement the helper**

```kotlin
package com.riffle.app.feature.reader

/** Whether the reader's readaloud control is shown, and whether it can be tapped. */
data class ReadaloudControlState(val visible: Boolean, val enabled: Boolean)

/**
 * Spec §6: Storyteller books always show an enabled control; a matched ABS book shows the
 * control but only enables it once the synced bundle is present; an unmatched ABS book shows
 * no control at all.
 */
fun readaloudControlState(
    isStoryteller: Boolean,
    isMatchedAbs: Boolean,
    bundlePresent: Boolean,
): ReadaloudControlState = when {
    isStoryteller -> ReadaloudControlState(visible = true, enabled = true)
    isMatchedAbs -> ReadaloudControlState(visible = true, enabled = bundlePresent)
    else -> ReadaloudControlState(visible = false, enabled = false)
}
```

- [ ] **Step 4: Run the test; verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.ReadaloudControlStateTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ReadaloudControlState.kt app/src/test/kotlin/com/riffle/app/feature/reader/ReadaloudControlStateTest.kt
git commit -m "feat(readaloud): pure helper for reader control visible/enabled state"
```

---

## Task 3: Server-explicit bundle download in the repository

The ABS detail screen must fetch from the **linked Storyteller server**, but `downloadAudio` today uses `serverRepository.getActive()` (the ABS server). Add a variant keyed by an explicit `serverId`; the existing method delegates.

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/ReadaloudAudioRepository.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ReadaloudAudioRepositoryImpl.kt`
- Modify: every other class implementing `ReadaloudAudioRepository` (test fakes).

- [ ] **Step 1: Add the interface method**

In `ReadaloudAudioRepository.kt`, add below the existing `downloadAudio`:

```kotlin
    /**
     * Downloads the synced bundle from a specific server (by id) rather than the active one.
     * Used by the ABS item-detail screen, where the active server is ABS but the bundle lives
     * on the linked Storyteller server. Keyed by [bookId] (the Storyteller book id).
     */
    suspend fun downloadAudio(
        bookId: String,
        serverId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudioDownloadResult
```

- [ ] **Step 2: Implement it and delegate the existing one**

In `ReadaloudAudioRepositoryImpl.kt`, replace the existing `downloadAudio(itemId, onProgress)` with:

```kotlin
    override suspend fun downloadAudio(
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudioDownloadResult {
        val activeId = serverRepository.getActive()?.id
            ?: return AudioDownloadResult.NetworkError(IllegalStateException("No active server"))
        return downloadAudio(itemId, activeId, onProgress)
    }

    override suspend fun downloadAudio(
        bookId: String,
        serverId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudioDownloadResult {
        if (downloadsStore.get(bookId) != null) return AudioDownloadResult.Success
        val server = serverRepository.getById(serverId)
            ?: return AudioDownloadResult.NetworkError(IllegalStateException("No server $serverId"))
        val token = tokenStorage.getToken(serverId)
            ?: return AudioDownloadResult.NetworkError(IllegalStateException("No token for $serverId"))
        return when (val r = downloader.download(server.url.value, bookId, token, server.insecureConnectionAllowed, onProgress)) {
            is AudiobookBundleDownloader.Result.Success -> AudioDownloadResult.Success
            is AudiobookBundleDownloader.Result.NetworkError -> AudioDownloadResult.NetworkError(r.cause)
        }
    }
```

(`serverRepository.getById` is already used elsewhere, e.g. `ThreePeerReaderSyncFactory`.)

- [ ] **Step 3: Update test fakes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugUnitTestKotlin :core:data:compileTestKotlin`
Expected: FAIL — any fake `ReadaloudAudioRepository` is now missing the new overload. For each reported fake, add:

```kotlin
    override suspend fun downloadAudio(
        bookId: String,
        serverId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudioDownloadResult = downloadAudio(bookId, onProgress)
```

- [ ] **Step 4: Verify compilation**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:data:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/domain core/data app
git commit -m "feat(readaloud): server-explicit downloadAudio for matched ABS items"
```

---

## Task 4: Reader VM — resolve link, key control by Storyteller book id

Make `EpubReaderViewModel` resolve the ABS book's `ReadaloudLink`, expose `readaloudVisible` (show-but-disable), keep `readaloudAvailable` as the **enabled** signal, and re-key audio lookups to the Storyteller book id.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

- [ ] **Step 1: Inject `ReadaloudLinkRepository`**

Add to the constructor (after `serverRepository`):

```kotlin
    private val readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository,
```

- [ ] **Step 2: Add the visible flow + audio-key field**

Next to `_readaloudAvailable` (≈ line 235):

```kotlin
    private val _readaloudVisible = MutableStateFlow(false)
    val readaloudVisible: StateFlow<Boolean> = _readaloudVisible

    // The id under which the synced bundle is stored. For a matched ABS book this is the linked
    // Storyteller book id; for a Storyteller book (or unmatched ABS) it is the opened itemId.
    private var audioBookId: String = itemId
```

- [ ] **Step 3: Replace the readaloud-availability init block**

Replace the block at ≈ lines 318–331 with:

```kotlin
            viewModelScope.launch {
                val activeServer = serverRepository.getActive()
                isStorytellerServer = activeServer?.serverType == ServerType.STORYTELLER

                // Matched ABS book → key the bundle by the linked Storyteller book id (the bundle
                // is stored under that id, not the ABS item id). Storyteller side keeps itemId.
                val link = if (!isStorytellerServer && activeServer != null) {
                    readaloudLinkRepository.findByAbsItem(activeServer.id, itemId)
                } else {
                    null
                }
                audioBookId = link?.storytellerBookId ?: itemId

                val control = readaloudControlState(
                    isStoryteller = isStorytellerServer,
                    isMatchedAbs = link != null,
                    bundlePresent = readaloudAudioRepository.isAudioAvailable(audioBookId),
                )
                _readaloudVisible.value = control.visible
                _readaloudAvailable.value = control.enabled

                // Annotations are ABS-side only (ADR 0024).
                if (!isStorytellerServer && activeServer != null) {
                    annotationServerId = activeServer.id
                    _annotationsAvailable.value = true
                    observeHighlights(activeServer.id)
                }
            }
```

- [ ] **Step 4: Re-key audio lookups from `itemId` to `audioBookId`**

In `onPlayTapped()` (≈ 878) change the bundle/probe lookups:

```kotlin
        val bundle = readaloudAudioRepository.bundleFile(audioBookId)
        ...
        _downloadPromptBytes.value = readaloudAudioRepository.probeSizeBytes(audioBookId) ?: 0L
```

Then find every remaining readaloud-audio reference that passes `itemId` and switch it to `audioBookId`:

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin` then search the file for `readaloudAudioRepository.` and `ensurePreparedAndPlay`. For each call that passes `itemId` to a readaloud-audio method (`bundleFile`, `readTrack`, `isAudioAvailable`, `probeSizeBytes`, `removeAudio`, or `ensurePreparedAndPlay`'s bundle), replace `itemId` with `audioBookId`. Leave non-audio `itemId` uses (library/session/position) untouched.

- [ ] **Step 5: Verify compilation**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(readaloud): reader control keyed by Storyteller book id, show-but-disable"
```

---

## Task 5: Reader screen — show-but-disable control with the new icon

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

- [ ] **Step 1: Collect the new flow**

Near the existing `val readaloudAvailable by viewModel.readaloudAvailable.collectAsState()` (≈ 188):

```kotlin
    val readaloudVisible by viewModel.readaloudVisible.collectAsState()
```

- [ ] **Step 2: Replace the control block**

Replace the block at ≈ 389–396:

```kotlin
            if (readaloudVisible) {
                IconButton(
                    onClick = viewModel::openReadaloud,
                    enabled = readaloudAvailable,
                    modifier = Modifier.testTag("readaloud_open"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_readaloud),
                        contentDescription = "Readaloud",
                    )
                }
            }
```

Add imports: `androidx.compose.ui.res.painterResource` and `com.riffle.app.R`. Remove the now-unused `import androidx.compose.material.icons.filled.Headphones`.

(`openReadaloud()` already early-returns when not available, so the disabled button is doubly safe — no error, no action.)

- [ ] **Step 3: Verify compilation**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(readaloud): reader top-bar control shows new icon, disabled until downloaded"
```

---

## Task 6: List-row badge → new icon

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt` (≈ 820–828)

- [ ] **Step 1: Swap the icon**

Replace the `Icon(...)` at ≈ 823–828 with:

```kotlin
                            Icon(
                                painter = painterResource(R.drawable.ic_readaloud),
                                contentDescription = "Has readaloud (synced narration)",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp),
                            )
```

Add imports `androidx.compose.ui.res.painterResource`, `com.riffle.app.R`. (Keep `Icons.Filled.Headphones` import only if still used elsewhere in the file — it is not after Task 7; remove when unused.)

- [ ] **Step 2: Verify compilation**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt
git commit -m "feat(readaloud): list-row badge uses new icon"
```

---

## Task 7: Grid cover overlay + remove C/D badges

Thread `linkedItemIds` down to `BookCoverTile`, render the readaloud icon top-right, and remove the "C"/"D" badges from grid covers (spec §3, decision B).

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt`
- Test: `app/src/androidTest/kotlin/com/riffle/app/feature/library/BookCoverTileReadaloudTest.kt`

- [ ] **Step 1: Write the failing UI test**

```kotlin
package com.riffle.app.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertDoesNotExist
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookCoverTileReadaloudTest {
    @get:Rule val rule = createComposeRule()

    private fun item() = LibraryItem(
        id = "abs-1", title = "Matched", author = "A", coverUrl = null, readingProgress = 0f,
        isCached = false, isDownloaded = false, ebookFormat = EbookFormat.Epub,
    )

    @Test fun shows_readaloud_icon_when_linked() {
        rule.setContent { BookCoverTile(item = item(), token = "", onClick = {}, hasReadaloudLink = true) }
        rule.onNodeWithContentDescription("Has readaloud (synced narration)").assertIsDisplayed()
    }

    @Test fun no_icon_when_not_linked() {
        rule.setContent { BookCoverTile(item = item(), token = "", onClick = {}, hasReadaloudLink = false) }
        rule.onNodeWithContentDescription("Has readaloud (synced narration)").assertDoesNotExist()
    }
}
```

(If the `LibraryItem` constructor has more required fields, copy the exact argument list from `LibraryItemDetailTabletLayoutTest`'s `item` builder.)

- [ ] **Step 2: Run it; verify it fails**

Run: `make harness-test` (filtered to phone). Expected: FAIL — `BookCoverTile` has no `hasReadaloudLink` parameter.

- [ ] **Step 3: Add the param + overlay; remove C/D badges**

In `BookCoverTile` (≈ 404), add the parameter:

```kotlin
fun BookCoverTile(
    item: LibraryItem,
    token: String,
    onClick: () -> Unit,
    hasReadaloudLink: Boolean = false,
) {
```

Replace the `Row` at `Alignment.TopEnd` (≈ 441–457, the C/D badges) with the readaloud overlay:

```kotlin
            if (hasReadaloudLink) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_readaloud),
                        contentDescription = "Has readaloud (synced narration)",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
```

Add imports: `androidx.compose.foundation.background`, `androidx.compose.foundation.shape.CircleShape`, `androidx.compose.ui.draw.clip`, `androidx.compose.ui.graphics.Color`, `androidx.compose.ui.res.painterResource`, `com.riffle.app.R`. Remove the now-unused `Badge` import if nothing else in the file uses it (the list-row card still uses `Badge`, so keep it).

- [ ] **Step 4: Thread `linkedItemIds` to the grid**

Add `linkedItemIds: Set<String> = emptySet()` to `BookSectionGrid` (≈ 302) and pass it down:

```kotlin
            BookCoverTile(
                item = item,
                token = token,
                onClick = { onItemSelected(item) },
                hasReadaloudLink = item.id in linkedItemIds,
            )
```

Add `linkedItemIds: Set<String> = emptySet()` to `HomeTabContent`, `ToReadTabContent`, and `AllBooksTabContent` (the composables that call `BookSectionGrid`), and forward it on every `BookSectionGrid(...)` call. Then at each of those tab-content call sites in the screen body, pass `linkedItemIds = linkedItemIds` (already collected at ≈ 124).

Run this to find every call site that now needs the argument:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin` — fix each "no value passed for parameter" by forwarding `linkedItemIds`.

- [ ] **Step 5: Run the UI test; verify it passes**

Run: `make harness-test`
Expected: PASS (`BookCoverTileReadaloudTest`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsScreen.kt app/src/androidTest/kotlin/com/riffle/app/feature/library/BookCoverTileReadaloudTest.kt
git commit -m "feat(readaloud): grid cover readaloud overlay; remove C/D badges"
```

---

## Task 8: Detail VM — readaloud link, download state, download/remove

Resolve the ABS book's link, expose a nullable `readaloudDownloadState` (null = not a matched ABS item → no button/indicator), and download/remove against the Storyteller identity. Remove the now-replaced `AbsHasReadaloud` footer.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailReadaloudDownloadTest.kt`

- [ ] **Step 1: Write the failing unit test**

```kotlin
package com.riffle.app.feature.library

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LibraryItemDetailReadaloudDownloadTest {

    private class FakeAudioRepo(private var present: Boolean) : ReadaloudAudioRepository {
        var lastDownloadBookId: String? = null
        var lastDownloadServerId: String? = null
        var lastRemoveBookId: String? = null
        override fun isAudioAvailable(itemId: String) = present
        override fun bundleFile(itemId: String): File? = if (present) File("x") else null
        override suspend fun readTrack(itemId: String): ReadaloudTrack? = null
        override suspend fun probeSizeBytes(itemId: String): Long? = null
        override suspend fun downloadAudio(itemId: String, onProgress: (Long, Long) -> Unit) =
            downloadAudio(itemId, "active", onProgress)
        override suspend fun downloadAudio(bookId: String, serverId: String, onProgress: (Long, Long) -> Unit): AudioDownloadResult {
            lastDownloadBookId = bookId; lastDownloadServerId = serverId; present = true
            return AudioDownloadResult.Success
        }
        override suspend fun removeAudio(itemId: String): Long { lastRemoveBookId = itemId; present = false; return 0L }
    }

    @Test fun download_state_maps_from_bundle_presence() {
        assertEquals(
            DownloadState.Downloaded,
            readaloudDownloadStateFor(bundlePresent = true),
        )
        assertEquals(
            DownloadState.NotDownloaded,
            readaloudDownloadStateFor(bundlePresent = false),
        )
    }
}
```

This test targets a small pure mapper `readaloudDownloadStateFor` we add alongside the VM (keeping the VM's heavy wiring out of the unit test). The fake documents the repository contract the VM will use.

- [ ] **Step 2: Run it; verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.library.LibraryItemDetailReadaloudDownloadTest"`
Expected: FAIL — unresolved `readaloudDownloadStateFor`.

- [ ] **Step 3: Add the mapper + VM wiring**

In `LibraryItemDetailViewModel.kt`, add a top-level helper:

```kotlin
internal fun readaloudDownloadStateFor(bundlePresent: Boolean): DownloadState =
    if (bundlePresent) DownloadState.Downloaded else DownloadState.NotDownloaded
```

Inject the audio repo (after `readaloudReviewRepository`):

```kotlin
    private val readaloudAudioRepository: com.riffle.core.domain.ReadaloudAudioRepository,
```

Add state + link:

```kotlin
    private var readaloudLink: com.riffle.core.domain.ReadaloudLink? = null
    private val _readaloudDownloadState = MutableStateFlow<DownloadState?>(null)
    val readaloudDownloadState: StateFlow<DownloadState?> = _readaloudDownloadState
```

In the loader (where `resolveReadaloudFooter` is invoked for the ABS item), after the item is ready and `serverId`/`isReadaloud` are known, add:

```kotlin
            val link = if (!isReadaloud && serverId != null) {
                readaloudLinkRepository.findByAbsItem(serverId, item.id)
            } else {
                null
            }
            readaloudLink = link
            _readaloudDownloadState.value = link?.let {
                readaloudDownloadStateFor(readaloudAudioRepository.isAudioAvailable(it.storytellerBookId))
            }
```

Add the actions:

```kotlin
    fun onDownloadReadaloud() {
        val link = readaloudLink ?: return
        if (_readaloudDownloadState.value == DownloadState.InProgress) return
        _readaloudDownloadState.value = DownloadState.InProgress
        viewModelScope.launch {
            val result = readaloudAudioRepository.downloadAudio(
                link.storytellerBookId, link.storytellerServerId,
            ) { _, _ -> }
            _readaloudDownloadState.value = readaloudDownloadStateFor(
                result is com.riffle.core.domain.AudioDownloadResult.Success,
            )
        }
    }

    fun onRemoveReadaloud() {
        val link = readaloudLink ?: return
        viewModelScope.launch {
            readaloudAudioRepository.removeAudio(link.storytellerBookId)
            _readaloudDownloadState.value = DownloadState.NotDownloaded
        }
    }
```

- [ ] **Step 4: Drop the `AbsHasReadaloud` footer state**

The ABS-side match is now shown by the indicator icon + download button, so:
- In `resolveReadaloudFooter`, change the ABS branch to `return null` (delete the `AbsHasReadaloud(...)` construction).
- Delete the `AbsHasReadaloud` data class from the `ReadaloudFooterState` sealed interface.
- (The footer's `when` branch for it is removed in Task 9.)

- [ ] **Step 5: Run the unit test; verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.library.LibraryItemDetailReadaloudDownloadTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt app/src/test/kotlin/com/riffle/app/feature/library/LibraryItemDetailReadaloudDownloadTest.kt
git commit -m "feat(readaloud): detail VM resolves link, exposes readaloud download state"
```

---

## Task 9: `ReadaloudDownloadButton` composable

Round 40dp control mirroring `DownloadButton`, but with a **download arrow + readaloud-glyph badge** (spec §5b chosen visual).

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/library/ReadaloudDownloadButton.kt`
- Test: `app/src/androidTest/kotlin/com/riffle/app/feature/library/ReadaloudDownloadButtonTest.kt`

- [ ] **Step 1: Write the failing UI test**

```kotlin
package com.riffle.app.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadaloudDownloadButtonTest {
    @get:Rule val rule = createComposeRule()

    @Test fun not_downloaded_tap_invokes_download() {
        var downloaded = false
        rule.setContent {
            ReadaloudDownloadButton(state = DownloadState.NotDownloaded, onDownload = { downloaded = true }, onRemove = {})
        }
        rule.onNodeWithContentDescription("Download readaloud").assertIsDisplayed().performClick()
        assert(downloaded)
    }

    @Test fun downloaded_tap_invokes_remove() {
        var removed = false
        rule.setContent {
            ReadaloudDownloadButton(state = DownloadState.Downloaded, onDownload = {}, onRemove = { removed = true })
        }
        rule.onNodeWithContentDescription("Remove readaloud download").assertIsDisplayed().performClick()
        assert(removed)
    }
}
```

- [ ] **Step 2: Run it; verify it fails**

Run: `make harness-test`. Expected: FAIL — unresolved `ReadaloudDownloadButton`.

- [ ] **Step 3: Implement the composable**

```kotlin
package com.riffle.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.riffle.app.R

/**
 * Downloads/removes the synced readaloud bundle for a matched ABS item. Mirrors [DownloadButton]
 * (tap to download, tap again to remove) but its glyph is a download arrow with the readaloud
 * book badge, so it reads as "download readaloud" next to the plain ebook download.
 */
@Composable
fun ReadaloudDownloadButton(
    state: DownloadState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val size = 40.dp
    when (state) {
        DownloadState.InProgress -> {
            Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(size))
            }
        }
        DownloadState.NotDownloaded -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(enabled = enabled, onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Download readaloud",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
                ReadaloudBadge(tint = MaterialTheme.colorScheme.outline)
            }
        }
        DownloadState.Downloaded -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Remove readaloud download",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                ReadaloudBadge(tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ReadaloudBadge(
    tint: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_readaloud),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
    }
}
```

- [ ] **Step 4: Run the UI test; verify it passes**

Run: `make harness-test`
Expected: PASS (`ReadaloudDownloadButtonTest`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/ReadaloudDownloadButton.kt app/src/androidTest/kotlin/com/riffle/app/feature/library/ReadaloudDownloadButtonTest.kt
git commit -m "feat(readaloud): readaloud download button (arrow + glyph badge)"
```

---

## Task 10: Detail screen — title indicator, download button, footer icon swap

Wire `readaloudDownloadState` into the detail screen: an indicator icon next to the title and the `ReadaloudDownloadButton` in the `ActionRow`; swap the footer's headphones icon for the new glyph; remove the deleted `AbsHasReadaloud` footer branch.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt`

- [ ] **Step 1: Collect the new state at the screen level**

In `LibraryItemDetailScreen(...)`, where other VM flows are collected:

```kotlin
    val readaloudDownloadState by viewModel.readaloudDownloadState.collectAsState()
```

- [ ] **Step 2: Thread it + callbacks into the content composables and `ActionRow`**

Add parameters to the phone content composable (`LibraryItemDetailContent`), the tablet one (`LibraryItemDetailContentTablet`), and `ActionRow`:

```kotlin
    readaloudDownloadState: DownloadState?,
    onDownloadReadaloud: () -> Unit,
    onRemoveReadaloud: () -> Unit,
    isOffline: Boolean,
```

Pass `readaloudDownloadState`, `viewModel::onDownloadReadaloud`, `viewModel::onRemoveReadaloud`, and the existing offline flag from the screen down through both content composables into `ActionRow`. (`isOffline` is already available where the `ActionRow`'s `readDisabledByOffline` is computed — reuse the same source.)

- [ ] **Step 3: Title indicator icon**

Replace the phone title line (≈ 282) and the tablet title line (≈ 439) with a `Row` that appends the indicator when matched:

```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (readaloudDownloadState != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_readaloud),
                        contentDescription = "Has readaloud (synced narration)",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
```

- [ ] **Step 4: Add the download button to `ActionRow`**

In `ActionRow`, after the existing ebook `DownloadButton(...)` call (≈ 551–555), add:

```kotlin
            if (readaloudDownloadState != null) {
                ReadaloudDownloadButton(
                    state = readaloudDownloadState,
                    onDownload = onDownloadReadaloud,
                    onRemove = onRemoveReadaloud,
                    enabled = !(isOffline && readaloudDownloadState == DownloadState.NotDownloaded),
                )
            }
```

- [ ] **Step 5: Footer icon swap + remove dead branch**

In `ReadaloudFooter`, replace the `Icon(imageVector = Icons.Filled.Headphones, …)` (≈ 339–344) with:

```kotlin
            Icon(
                painter = painterResource(R.drawable.ic_readaloud),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
```

Remove the `is ReadaloudFooterState.AbsHasReadaloud -> { … }` branch from the footer's `when` (the type was deleted in Task 8). Add imports `androidx.compose.ui.res.painterResource`, `com.riffle.app.R`; remove `import androidx.compose.material.icons.filled.Headphones`.

- [ ] **Step 6: Verify compilation + fix call sites**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Fix any "no value passed for parameter" by forwarding the four new params from the screen through both content composables.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt
git commit -m "feat(readaloud): detail title indicator + readaloud download button + footer icon"
```

---

## Task 11: Full build + test sweep

- [ ] **Step 1: Unit tests**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`
Expected: BUILD SUCCESSFUL (CI uses `./gradlew test`; module-specific `:testDebugUnitTest` misses pure-JVM `:test`).

- [ ] **Step 2: Harness UI tests (phone)**

Run: `make harness-test`
Expected: PASS, including `BookCoverTileReadaloudTest` and `ReadaloudDownloadButtonTest`.

- [ ] **Step 3: Manual smoke (real app)**

Confirm against the ABS test server (`http://media-server:13378`, test/test) and Storyteller (`http://media-server:8001`):
- A matched ABS book shows the new glyph on its grid cover (no C/D badges) and next to its title in detail.
- Detail shows the readaloud download button; tapping downloads (spinner → filled); tapping again removes.
- A bundle downloaded from the Storyteller library shows the ABS button already in the **Downloaded** state.
- In the reader, a matched ABS book's readaloud control is **disabled** before download and **enabled** after; pressing it while disabled does nothing (no error).

- [ ] **Step 4: Commit any fixes**

```bash
git add -A && git commit -m "test(readaloud): fixes from full build + harness sweep"
```

---

## Self-Review Notes

- **Spec coverage:** icon (Task 1) → 3 swaps (Tasks 5/6/10) + grid overlay (7); C/D removal (7); detail footer→indicator (8/10); download button keyed by Storyteller id (3/8/9/10); reader disable-until-downloaded (2/4/5); shared-bundle reflection (3/4/8 — all key by `storytellerBookId`). Shelved launch button is correctly absent.
- **Type consistency:** `DownloadState` (NotDownloaded/InProgress/Downloaded) reused for both ebook and readaloud; `readaloudDownloadState` is nullable (`null` = not a matched ABS item); `readaloudControlState`/`ReadaloudControlState` names match across Tasks 2/4; `downloadAudio(bookId, serverId, onProgress)` signature matches across Tasks 3/8.
- **Risk note:** Task 4 re-keys reader audio refs by grep — the implementer must verify every readaloud-audio `itemId` use is switched while leaving library/session/position `itemId` uses intact.
